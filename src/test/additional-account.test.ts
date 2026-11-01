import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SupabaseClient } from '@supabase/supabase-js';
import { createAdditionalAccountClient, verifyAdditionalAccount } from '../lib/additional-account';
import { additionalBackend } from '../lib/additional-backend';

afterEach(() => vi.unstubAllGlobals());
describe('additional account security', () => {
  it('keeps private data writes blocked even through the authentication client', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    const { error } = await createAdditionalAccountClient().from('tunnel_profiles').insert({ user_id: 'forged' });
    expect(error?.message).toContain('locked'); expect(fetch).not.toHaveBeenCalled();
  });
  it('sends email verification only to the external project without a Cloud bearer', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetch);
    await createAdditionalAccountClient().auth.signInWithOtp({ email: 'owner@example.com' });
    const call = fetch.mock.calls[0]; if (!call) throw new Error('Expected auth request');
    expect(String(call[0])).toBe(`${additionalBackend.url}/auth/v1/otp`);
    expect(new Headers(call[1].headers).has('Authorization')).toBe(false);
  });
  it('rejects unconfirmed external identity returned by the auth service', async () => {
    const client = { auth: { getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'external', email_confirmed_at: null } }, error: null }) } } as unknown as SupabaseClient;
    await expect(verifyAdditionalAccount(client)).rejects.toThrow('Confirm');
  });
  it('rejects anonymous accounts even if confirmation metadata exists', async () => {
    const client = { auth: { getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'external', is_anonymous: true, email_confirmed_at: '2026-11-01' } }, error: null }) } } as unknown as SupabaseClient;
    await expect(verifyAdditionalAccount(client)).rejects.toThrow('Confirm');
  });
  it('accepts only identity verified by a successful live auth lookup', async () => {
    const getUser = vi.fn().mockResolvedValue({ data: { user: { id: 'external-owner', email: 'owner@example.com', email_confirmed_at: '2026-11-01', is_anonymous: false } }, error: null });
    const client = { auth: { getUser } } as unknown as SupabaseClient;
    await expect(verifyAdditionalAccount(client)).resolves.toEqual({ id: 'external-owner', email: 'owner@example.com' });
    expect(getUser).toHaveBeenCalledOnce();
  });
});