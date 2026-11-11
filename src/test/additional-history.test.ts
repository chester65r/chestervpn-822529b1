import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SupabaseClient } from '@supabase/supabase-js';
import { createAdditionalHistoryClient, listHistory, recordHistory } from '../lib/additional-history';
import { additionalBackend } from '../lib/additional-backend';

afterEach(() => vi.unstubAllGlobals());

const confirmedOwner = {
  data: { user: { id: 'owner', email: 'owner@example.com', email_confirmed_at: '2026-11-01', is_anonymous: false } },
  error: null,
};

function listQuery(data: unknown) {
  const limit = vi.fn(() => Promise.resolve({ data, error: null }));
  const order = vi.fn(() => ({ limit }));
  const eq = vi.fn(() => ({ order }));
  const select = vi.fn(() => ({ eq }));
  return { select, eq, order, limit };
}

function insertQuery() {
  const row = { id: 'h1', source: 'simulation', status: 'connected', error_category: null, occurred_at: '2026-11-11T05:00:00Z' };
  const single = vi.fn(() => Promise.resolve({ data: row, error: null }));
  const select = vi.fn(() => ({ single }));
  const insert = vi.fn(() => ({ select }));
  return { insert, select, single };
}

describe('owner-confirmed history operations', () => {
  it('rejects an unconfirmed identity before any table query runs', async () => {
    const from = vi.fn();
    const client = { auth: { getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'external', email_confirmed_at: null } }, error: null }) }, from } as unknown as SupabaseClient;
    await expect(listHistory(client)).rejects.toThrow('Confirm');
    await expect(recordHistory(client, { source: 'simulation', status: 'connected' })).rejects.toThrow('Confirm');
    expect(from).not.toHaveBeenCalled();
  });
  it('lists only the confirmed owner rows, latest first, with a bounded limit', async () => {
    const builder = listQuery([]);
    const client = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => builder) } as unknown as SupabaseClient;
    await listHistory(client);
    expect(builder.eq).toHaveBeenCalledWith('user_id', 'owner');
    expect(builder.order).toHaveBeenCalledWith('occurred_at', { ascending: false });
    expect(builder.limit).toHaveBeenCalledWith(20);
    const big = listQuery([]);
    const bigClient = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => big) } as unknown as SupabaseClient;
    await listHistory(bigClient, 500);
    expect(big.limit).toHaveBeenCalledWith(100);
  });
  it('records events with exactly the table columns, stamping error_category only for errors', async () => {
    const plain = insertQuery();
    const plainClient = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => plain) } as unknown as SupabaseClient;
    await recordHistory(plainClient, { source: 'simulation', status: 'connected' });
    expect(plain.insert).toHaveBeenCalledWith({ source: 'simulation', status: 'connected', error_category: null });

    const withCategory = insertQuery();
    const withClient = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => withCategory) } as unknown as SupabaseClient;
    await recordHistory(withClient, { source: 'native', status: 'error', errorCategory: 'network' });
    expect(withCategory.insert).toHaveBeenCalledWith({ source: 'native', status: 'error', error_category: 'network' });

    const bareError = insertQuery();
    const bareClient = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => bareError) } as unknown as SupabaseClient;
    await recordHistory(bareClient, { source: 'simulation', status: 'error' });
    expect(bareError.insert).toHaveBeenCalledWith({ source: 'simulation', status: 'error', error_category: 'unknown' });

    const mislabeled = insertQuery();
    const mislabeledClient = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => mislabeled) } as unknown as SupabaseClient;
    await recordHistory(mislabeledClient, { source: 'simulation', status: 'disconnected', errorCategory: 'timeout' });
    expect(mislabeled.insert).toHaveBeenCalledWith({ source: 'simulation', status: 'disconnected', error_category: null });
  });
  it('rejects values outside the table check constraints before touching the client', async () => {
    const from = vi.fn();
    const getUser = vi.fn();
    const client = { auth: { getUser }, from } as unknown as SupabaseClient;
    await expect(recordHistory(client, { source: 'cloud' as never, status: 'connected' })).rejects.toThrow('could not be recorded');
    await expect(recordHistory(client, { source: 'simulation', status: 'connecting' as never })).rejects.toThrow('could not be recorded');
    await expect(recordHistory(client, { source: 'simulation', status: 'error', errorCategory: 'oops' as never })).rejects.toThrow('could not be recorded');
    expect(from).not.toHaveBeenCalled();
    expect(getUser).not.toHaveBeenCalled();
  });
});

describe('history client request guard', () => {
  it('blocks other tables and PATCH (no update grant) before any request is made', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const client = createAdditionalHistoryClient();
    const profiles = await client.from('tunnel_profiles').insert({ preset: 'custom', port: 443 });
    expect(profiles.error?.message).toContain('locked');
    const patch = await client.from('tunnel_connection_history').update({ status: 'connected' }).eq('id', 'h1');
    expect(patch.error?.message).toContain('locked');
    expect(fetch).not.toHaveBeenCalled();
  });
  it('sends history reads only to tunnel_connection_history without a publishable-key bearer', async () => {
    const request = vi.fn().mockResolvedValue(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', request);
    await createAdditionalHistoryClient().from('tunnel_connection_history').select('status');
    expect(request).toHaveBeenCalledOnce();
    const call = request.mock.calls[0];
    if (!call) throw new Error('Expected a history read request');
    expect(String(call[0])).toBe(`${additionalBackend.url}/rest/v1/tunnel_connection_history?select=status`);
    expect(new Headers(call[1].headers).has('Authorization')).toBe(false);
    expect(new Headers(call[1].headers).get('apikey')).toBe(additionalBackend.publishableKey);
  });
});
