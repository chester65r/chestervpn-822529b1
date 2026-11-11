import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SupabaseClient } from '@supabase/supabase-js';
import { adoptVerifiedSession, createAdditionalHealthClient, fetchLatestServerHealth, latestHealthByServer, type ServerHealthRow } from '../lib/additional-monitoring';
import { additionalBackend } from '../lib/additional-backend';

afterEach(() => vi.unstubAllGlobals());

const row = (server_id: string, observed_at: string, status: ServerHealthRow['status'] = 'healthy'): ServerHealthRow => ({
  id: `${server_id}-${observed_at}`, server_id, status, latency_ms: 42, observed_at,
});

function healthQuery(data: unknown) {
  const limit = vi.fn(() => Promise.resolve({ data, error: null }));
  const order = vi.fn(() => ({ limit }));
  const eq = vi.fn(() => ({ order }));
  const select = vi.fn(() => ({ eq }));
  return { select, eq, order, limit };
}

const confirmedOwner = {
  data: { user: { id: 'owner', email: 'owner@example.com', email_confirmed_at: '2026-11-01', is_anonymous: false } },
  error: null,
};

describe('latest health per server', () => {
  it('keeps only the newest report per server, newest first, regardless of input order', () => {
    const rows = [
      row('srv-b', '2026-11-11T01:00:00Z'),
      row('srv-a', '2026-11-11T03:00:00Z', 'degraded'),
      row('srv-a', '2026-11-11T02:00:00Z'),
      row('srv-b', '2026-11-11T04:00:00Z', 'unreachable'),
    ];
    const latest = latestHealthByServer(rows);
    expect(latest.map(r => `${r.server_id}@${r.observed_at}`)).toEqual([
      'srv-b@2026-11-11T04:00:00Z',
      'srv-a@2026-11-11T03:00:00Z',
    ]);
    expect(latest[1]?.status).toBe('degraded');
  });
  it('returns an empty list when the collector has posted nothing', () => {
    expect(latestHealthByServer([])).toEqual([]);
  });
});

describe('owner-confirmed health reads', () => {
  it('rejects an unconfirmed identity before any table query runs', async () => {
    const from = vi.fn();
    const client = { auth: { getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'external', email_confirmed_at: null } }, error: null }) }, from } as unknown as SupabaseClient;
    await expect(fetchLatestServerHealth(client)).rejects.toThrow('Confirm');
    expect(from).not.toHaveBeenCalled();
  });
  it('loads only the confirmed owner rows and reduces them to latest per server', async () => {
    const builder = healthQuery([row('srv-a', '2026-11-11T02:00:00Z'), row('srv-a', '2026-11-11T03:00:00Z', 'degraded')]);
    const client = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => builder) } as unknown as SupabaseClient;
    const latest = await fetchLatestServerHealth(client);
    expect(builder.eq).toHaveBeenCalledWith('user_id', 'owner');
    expect(latest).toHaveLength(1);
    expect(latest[0]?.status).toBe('degraded');
  });
  it('adopts only a live session from the verified account client', async () => {
    const setSession = vi.fn().mockResolvedValue({ error: null });
    const healthClient = { auth: { setSession } } as unknown as SupabaseClient;
    const accountClient = { auth: { getSession: vi.fn().mockResolvedValue({ data: { session: { access_token: 'at', refresh_token: 'rt' } }, error: null }) } } as unknown as SupabaseClient;
    await expect(adoptVerifiedSession(healthClient, accountClient)).resolves.toBeUndefined();
    expect(setSession).toHaveBeenCalledWith({ access_token: 'at', refresh_token: 'rt' });
    const noSession = { auth: { getSession: vi.fn().mockResolvedValue({ data: { session: null }, error: null }) } } as unknown as SupabaseClient;
    await expect(adoptVerifiedSession(healthClient, noSession)).rejects.toThrow('Verify');
  });
});

describe('health client request guard', () => {
  it('blocks writes to the health table before any request is made', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    const { error } = await createAdditionalHealthClient().from('tunnel_server_health').insert({ user_id: 'forged', server_id: 'srv', status: 'healthy' });
    expect(error?.message).toContain('locked');
    expect(fetch).not.toHaveBeenCalled();
  });
  it('blocks access to every other additional-backend table', async () => {
    // Probed with a write, like the account-client isolation tests: in this
    // stack a rejected PostgREST GET never settles, while writes surface the
    // guard's error immediately. The guard itself rejects every non-allowlisted
    // path (reads included) before fetch is ever called.
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    const { error } = await createAdditionalHealthClient().from('tunnel_profiles').insert({ user_id: 'forged' });
    expect(error?.message).toContain('locked');
    expect(fetch).not.toHaveBeenCalled();
  });
  it('sends health reads only to the additional project without a publishable-key bearer', async () => {
    const request = vi.fn().mockResolvedValue(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', request);
    await createAdditionalHealthClient().from('tunnel_server_health').select('status');
    expect(request).toHaveBeenCalledOnce();
    const call = request.mock.calls[0]; if (!call) throw new Error('Expected a health read request');
    expect(String(call[0])).toBe(`${additionalBackend.url}/rest/v1/tunnel_server_health?select=status`);
    expect(new Headers(call[1].headers).has('Authorization')).toBe(false);
    expect(new Headers(call[1].headers).get('apikey')).toBe(additionalBackend.publishableKey);
  });
});
