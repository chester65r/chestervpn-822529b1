import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SupabaseClient } from '@supabase/supabase-js';
import {
  createAdditionalProfilesClient,
  deleteProfile,
  listProfiles,
  readLocalSavedProfiles,
  syncProfiles,
  toRemoteProfile,
  upsertProfile,
  type RemoteProfile,
} from '../lib/additional-profiles';
import { additionalBackend } from '../lib/additional-backend';
import { SAVED_PROFILES_KEY, serializeSavedProfiles, type SavedProfile } from '../lib/tunnel';

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

const savedProfile = (presetId: string, port: string): SavedProfile => ({
  id: `local-${presetId}-${port}`,
  name: 'Test profile',
  presetId,
  profile: { ip: '192.0.2.10', port, sni: 'example.com', payload: '' },
  savedAt: 1,
});

describe('local profile mapping', () => {
  it('maps custom and named route presets onto the table preset values', () => {
    expect(toRemoteProfile(savedProfile('custom', '443'))).toEqual({ preset: 'custom', port: 443 });
    expect(toRemoteProfile(savedProfile('alpha', '8443'))).toEqual({ preset: 'standard-tls', port: 8443 });
    expect(toRemoteProfile(savedProfile('beta', '443'))).toEqual({ preset: 'standard-tls', port: 443 });
    expect(toRemoteProfile(savedProfile('gamma', '51820'))).toEqual({ preset: 'standard-tls', port: 51820 });
    expect(toRemoteProfile(savedProfile('something-else', '443'))).toEqual({ preset: 'custom', port: 443 });
  });
  it('trims the port and rejects ports the table check constraint would reject', () => {
    expect(toRemoteProfile(savedProfile('custom', ' 443 '))).toEqual({ preset: 'custom', port: 443 });
    expect(toRemoteProfile(savedProfile('custom', ''))).toBeNull();
    expect(toRemoteProfile(savedProfile('custom', 'abc'))).toBeNull();
    expect(toRemoteProfile(savedProfile('custom', '0'))).toBeNull();
    expect(toRemoteProfile(savedProfile('custom', '65536'))).toBeNull();
    expect(toRemoteProfile(savedProfile('custom', '44.3'))).toBeNull();
  });
  it('reads the real local saved-profiles store and drops malformed entries', () => {
    window.localStorage.setItem(SAVED_PROFILES_KEY, serializeSavedProfiles([savedProfile('alpha', '8443')]));
    expect(readLocalSavedProfiles()).toHaveLength(1);
    window.localStorage.setItem(SAVED_PROFILES_KEY, '[{"id":1},null,"junk"]');
    expect(readLocalSavedProfiles()).toEqual([]);
    window.localStorage.removeItem(SAVED_PROFILES_KEY);
    expect(readLocalSavedProfiles()).toEqual([]);
  });
});

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

describe('owner-confirmed profile operations', () => {
  it('rejects an unconfirmed identity before any table query runs', async () => {
    const from = vi.fn();
    const client = { auth: { getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'external', email_confirmed_at: null } }, error: null }) }, from } as unknown as SupabaseClient;
    await expect(listProfiles(client)).rejects.toThrow('Confirm');
    await expect(upsertProfile(client, { preset: 'custom', port: 443 })).rejects.toThrow('Confirm');
    await expect(deleteProfile(client, 'row-1')).rejects.toThrow('Confirm');
    expect(from).not.toHaveBeenCalled();
  });
  it('lists only the confirmed owner rows, newest first', async () => {
    const rows = [{ id: 'r1', preset: 'custom', port: 443, created_at: '2026-11-11T01:00:00Z' }];
    const builder = listQuery(rows);
    const client = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => builder) } as unknown as SupabaseClient;
    await expect(listProfiles(client)).resolves.toEqual(rows);
    expect(builder.eq).toHaveBeenCalledWith('user_id', 'owner');
    expect(builder.order).toHaveBeenCalledWith('created_at', { ascending: false });
    expect(builder.limit).toHaveBeenCalledWith(200);
  });
  it('inserts exactly the writable columns (user_id stays a server default)', async () => {
    const single = vi.fn(() => Promise.resolve({ data: { id: 'r9', preset: 'standard-tls', port: 8443, created_at: '2026-11-11T02:00:00Z' }, error: null }));
    const select = vi.fn(() => ({ single }));
    const insert = vi.fn(() => ({ select }));
    const client = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => ({ insert })) } as unknown as SupabaseClient;
    const row = await upsertProfile(client, { preset: 'standard-tls', port: 8443 });
    expect(insert).toHaveBeenCalledWith({ preset: 'standard-tls', port: 8443 });
    expect(row.id).toBe('r9');
  });
  it('rejects values the table would reject before touching the client', async () => {
    const from = vi.fn();
    const getUser = vi.fn();
    const client = { auth: { getUser }, from } as unknown as SupabaseClient;
    await expect(upsertProfile(client, { preset: 'custom', port: 70000 })).rejects.toThrow('could not be saved');
    await expect(upsertProfile(client, { preset: 'websocket', port: 0 })).rejects.toThrow('could not be saved');
    await expect(upsertProfile(client, { preset: 'bogus' as never, port: 443 })).rejects.toThrow('could not be saved');
    expect(from).not.toHaveBeenCalled();
    expect(getUser).not.toHaveBeenCalled();
  });
  it('deletes one owner row by id, scoped to the owner', async () => {
    const secondEq = vi.fn(() => Promise.resolve({ error: null }));
    const firstEq = vi.fn(() => ({ eq: secondEq }));
    const del = vi.fn(() => ({ eq: firstEq }));
    const client = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => ({ delete: del })) } as unknown as SupabaseClient;
    await expect(deleteProfile(client, 'row-7')).resolves.toBeUndefined();
    expect(firstEq).toHaveBeenCalledWith('id', 'row-7');
    expect(secondEq).toHaveBeenCalledWith('user_id', 'owner');
  });
});

describe('profile sync', () => {
  function fakeClient(initial: RemoteProfile[]) {
    const rows: RemoteProfile[] = [...initial];
    const builder = {
      select: vi.fn(() => builder),
      eq: vi.fn(() => builder),
      order: vi.fn(() => builder),
      limit: vi.fn(() => Promise.resolve({ data: [...rows], error: null })),
      insert: vi.fn((input: { preset: string; port: number }) => {
        builder.pending = input;
        return builder;
      }),
      single: vi.fn(() => {
        const row: RemoteProfile = { id: `remote-${rows.length + 1}`, preset: builder.pending.preset as RemoteProfile['preset'], port: builder.pending.port, created_at: '2026-11-11T05:00:00Z' };
        rows.push(row);
        return Promise.resolve({ data: row, error: null });
      }),
      pending: { preset: 'custom', port: 443 },
    };
    return { client: { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, from: vi.fn(() => builder) } as unknown as SupabaseClient, rows };
  }

  it('uploads only the missing (preset, port) pairs and counts the rest honestly', async () => {
    const { client, rows } = fakeClient([{ id: 'remote-1', preset: 'custom', port: 443, created_at: '2026-11-11T01:00:00Z' }]);
    const result = await syncProfiles(client, [
      savedProfile('custom', '443'), // already stored remotely
      savedProfile('alpha', '8443'), // new: standard-tls 8443
      savedProfile('beta', '8443'), // same pair as alpha once mapped
      savedProfile('custom', 'not-a-port'), // unmappable
    ]);
    expect(result.uploaded).toBe(1);
    expect(result.alreadyPresent).toBe(2);
    expect(result.skipped).toBe(1);
    expect(result.profiles).toHaveLength(2);
    expect(rows.map((row) => `${row.preset}:${row.port}`).sort()).toEqual(['custom:443', 'standard-tls:8443']);
  });
  it('uploads nothing when the local store is empty', async () => {
    const { client } = fakeClient([]);
    const result = await syncProfiles(client, []);
    expect(result).toEqual({ uploaded: 0, alreadyPresent: 0, skipped: 0, profiles: [] });
  });
});

describe('profiles client request guard', () => {
  it('blocks every other additional-backend table before any request is made', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const client = createAdditionalProfilesClient();
    const health = await client.from('tunnel_server_health').insert({ user_id: 'forged', server_id: 'srv', status: 'healthy' });
    expect(health.error?.message).toContain('locked');
    const history = await client.from('tunnel_connection_history').insert({ source: 'native', status: 'connected' });
    expect(history.error?.message).toContain('locked');
    expect(fetch).not.toHaveBeenCalled();
  });
  it('sends profile reads and writes only to tunnel_profiles without a publishable-key bearer', async () => {
    const request = vi.fn().mockResolvedValue(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', request);
    await createAdditionalProfilesClient().from('tunnel_profiles').select('preset');
    expect(request).toHaveBeenCalledOnce();
    const read = request.mock.calls[0];
    if (!read) throw new Error('Expected a profile read request');
    expect(String(read[0])).toBe(`${additionalBackend.url}/rest/v1/tunnel_profiles?select=preset`);
    expect(new Headers(read[1].headers).has('Authorization')).toBe(false);
    expect(new Headers(read[1].headers).get('apikey')).toBe(additionalBackend.publishableKey);

    request.mockClear();
    request.mockResolvedValue(new Response(null, { status: 201 }));
    await createAdditionalProfilesClient().from('tunnel_profiles').update({ port: 8443 }).eq('id', 'row-1');
    expect(request).toHaveBeenCalledOnce();
    const write = request.mock.calls[0];
    if (!write) throw new Error('Expected a profile write request');
    expect(String(write[0])).toBe(`${additionalBackend.url}/rest/v1/tunnel_profiles?id=eq.row-1`);
    expect(write[1].method).toBe('PATCH');
  });
});
