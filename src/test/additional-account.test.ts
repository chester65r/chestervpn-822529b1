import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SupabaseClient } from '@supabase/supabase-js';
import { additionalEmailRedirectTo, checkAdditionalEmailVerification, completeAdditionalEmailReturn, confirmAdditionalEmailCode, createAdditionalAccountClient, hasAdditionalEmailReturnMarker, parseAdditionalEmailReturn, stripAdditionalEmailReturn, verifyAdditionalAccount } from '../lib/additional-account';
import { additionalBackend } from '../lib/additional-backend';

afterEach(() => vi.unstubAllGlobals());
describe('additional account security', () => {
  it('blocks automatic email confirmation as ownership proof', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ external: { email: true }, mailer_autoconfirm: true, disable_signup: false })));
    await expect(checkAdditionalEmailVerification()).rejects.toThrow('confirmation must be enabled');
  });
  it('fails closed when hosted email settings cannot be read', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })));
    await expect(checkAdditionalEmailVerification()).rejects.toThrow('could not be checked');
  });
  it('requires successful email OTP before live identity lookup', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ external: { email: true }, mailer_autoconfirm: false, disable_signup: false })));
    const getUser = vi.fn();
    const client = { auth: { verifyOtp: vi.fn().mockResolvedValue({ data: { user: null, session: null }, error: new Error('wrong code') }), getUser } } as unknown as SupabaseClient;
    await expect(confirmAdditionalEmailCode(client, 'owner@example.com', 'wrong-code')).rejects.toThrow('confirmation failed');
    expect(getUser).not.toHaveBeenCalled();
  });
  it('rejects an identity changed between email challenge and live lookup', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ external: { email: true }, mailer_autoconfirm: false, disable_signup: false })));
    const client = { auth: { verifyOtp: vi.fn().mockResolvedValue({ data: { user: { id: 'first' }, session: {} }, error: null }), getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'other', email_confirmed_at: '2026-11-01' } }, error: null }) } } as unknown as SupabaseClient;
    await expect(confirmAdditionalEmailCode(client, 'owner@example.com', 'code')).rejects.toThrow('identity changed');
  });
  it('completes email proof only when challenge and live identity agree', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ external: { email: true }, mailer_autoconfirm: false, disable_signup: false })));
    const verifyOtp = vi.fn().mockResolvedValue({ data: { user: { id: 'owner' }, session: {} }, error: null });
    const client = { auth: { verifyOtp, getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'owner', email: 'owner@example.com', email_confirmed_at: '2026-11-01', is_anonymous: false } }, error: null }) } } as unknown as SupabaseClient;
    await expect(confirmAdditionalEmailCode(client, 'owner@example.com', 'email-code')).resolves.toEqual({ id: 'owner', email: 'owner@example.com' });
    expect(verifyOtp).toHaveBeenCalledWith({ email: 'owner@example.com', token: 'email-code', type: 'email' });
  });
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

const settingsOk = () => Response.json({ external: { email: true }, mailer_autoconfirm: false, disable_signup: false });
const confirmedOwner = { id: 'owner', email: 'owner@example.com', email_confirmed_at: '2026-11-01', is_anonymous: false };

describe('additional account email-link return', () => {
  it('builds the confirmation redirect onto the app origin with the return marker', () => {
    expect(additionalEmailRedirectTo('https://chestervpn-822529b1.vercel.app')).toBe('https://chestervpn-822529b1.vercel.app/?additional_confirm=1');
  });
  it('recognizes the marker only on additional-backend returns', () => {
    expect(hasAdditionalEmailReturnMarker(new URL('https://app.example/?additional_confirm=1'))).toBe(true);
    expect(hasAdditionalEmailReturnMarker(new URL('https://app.example/?token_hash=abc&type=email'))).toBe(false);
    expect(parseAdditionalEmailReturn(new URL('https://app.example/?token_hash=abc&type=email'))).toEqual({ kind: 'none' });
  });
  it('parses token_hash returns and keeps a valid return type', () => {
    expect(parseAdditionalEmailReturn(new URL('https://app.example/?additional_confirm=1&token_hash=hash-1&type=magiclink'))).toEqual({ kind: 'token_hash', tokenHash: 'hash-1', type: 'magiclink' });
    expect(parseAdditionalEmailReturn(new URL('https://app.example/?additional_confirm=1&token_hash=hash-2'))).toEqual({ kind: 'token_hash', tokenHash: 'hash-2', type: 'email' });
    expect(parseAdditionalEmailReturn(new URL('https://app.example/?additional_confirm=1&token_hash=hash-3&type=bogus'))).toEqual({ kind: 'token_hash', tokenHash: 'hash-3', type: 'email' });
  });
  it('parses a full session return from the URL fragment', () => {
    expect(parseAdditionalEmailReturn(new URL('https://app.example/?additional_confirm=1#access_token=at&refresh_token=rt&token_type=bearer&type=magiclink'))).toEqual({ kind: 'session', accessToken: 'at', refreshToken: 'rt' });
    expect(parseAdditionalEmailReturn(new URL('https://app.example/?additional_confirm=1#access_token=at'))).toEqual({ kind: 'none' });
    expect(parseAdditionalEmailReturn(new URL('https://app.example/?additional_confirm=1'))).toEqual({ kind: 'none' });
  });
  it('ignores URLs that are not additional-backend returns', async () => {
    const verifyOtp = vi.fn(); const setSession = vi.fn();
    const client = { auth: { verifyOtp, setSession } } as unknown as SupabaseClient;
    await expect(completeAdditionalEmailReturn(client, new URL('https://app.example/'))).resolves.toBeNull();
    expect(verifyOtp).not.toHaveBeenCalled(); expect(setSession).not.toHaveBeenCalled();
  });
  it('completes a token_hash return on the additional client, then applies the live gate', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(settingsOk()));
    const verifyOtp = vi.fn().mockResolvedValue({ data: { user: { id: 'owner' }, session: {} }, error: null });
    const setSession = vi.fn();
    const client = { auth: { verifyOtp, setSession, getUser: vi.fn().mockResolvedValue({ data: { user: confirmedOwner }, error: null }) } } as unknown as SupabaseClient;
    await expect(completeAdditionalEmailReturn(client, new URL('https://app.example/?additional_confirm=1&token_hash=hash-1&type=email'))).resolves.toEqual({ id: 'owner', email: 'owner@example.com' });
    expect(verifyOtp).toHaveBeenCalledWith({ token_hash: 'hash-1', type: 'email' });
    expect(setSession).not.toHaveBeenCalled();
  });
  it('adopts a fragment session return into the additional client, then applies the live gate', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(settingsOk()));
    const setSession = vi.fn().mockResolvedValue({ data: { user: { id: 'owner' }, session: {} }, error: null });
    const verifyOtp = vi.fn();
    const client = { auth: { verifyOtp, setSession, getUser: vi.fn().mockResolvedValue({ data: { user: confirmedOwner }, error: null }) } } as unknown as SupabaseClient;
    await expect(completeAdditionalEmailReturn(client, new URL('https://app.example/?additional_confirm=1#access_token=at&refresh_token=rt'))).resolves.toEqual({ id: 'owner', email: 'owner@example.com' });
    expect(setSession).toHaveBeenCalledWith({ access_token: 'at', refresh_token: 'rt' });
    expect(verifyOtp).not.toHaveBeenCalled();
  });
  it('fails closed on the link path when hosted email settings are unsafe', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ external: { email: true }, mailer_autoconfirm: true, disable_signup: false })));
    const verifyOtp = vi.fn(); const setSession = vi.fn();
    const client = { auth: { verifyOtp, setSession } } as unknown as SupabaseClient;
    await expect(completeAdditionalEmailReturn(client, new URL('https://app.example/?additional_confirm=1&token_hash=hash-1'))).rejects.toThrow('confirmation must be enabled');
    expect(verifyOtp).not.toHaveBeenCalled(); expect(setSession).not.toHaveBeenCalled();
  });
  it('link path still rejects unconfirmed or anonymous live identities', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(settingsOk())));
    const verifyOtp = vi.fn().mockResolvedValue({ data: { user: { id: 'owner' }, session: {} }, error: null });
    const unconfirmed = { auth: { verifyOtp, getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'owner', email_confirmed_at: null, is_anonymous: false } }, error: null }) } } as unknown as SupabaseClient;
    await expect(completeAdditionalEmailReturn(unconfirmed, new URL('https://app.example/?additional_confirm=1&token_hash=hash-1'))).rejects.toThrow('Confirm');
    const anonymous = { auth: { verifyOtp, getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'owner', email_confirmed_at: '2026-11-01', is_anonymous: true } }, error: null }) } } as unknown as SupabaseClient;
    await expect(completeAdditionalEmailReturn(anonymous, new URL('https://app.example/?additional_confirm=1&token_hash=hash-1'))).rejects.toThrow('Confirm');
  });
  it('link path rejects an identity changed between challenge and live lookup', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(settingsOk()));
    const client = { auth: { verifyOtp: vi.fn().mockResolvedValue({ data: { user: { id: 'first' }, session: {} }, error: null }), getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'other', email: 'owner@example.com', email_confirmed_at: '2026-11-01', is_anonymous: false } }, error: null }) } } as unknown as SupabaseClient;
    await expect(completeAdditionalEmailReturn(client, new URL('https://app.example/?additional_confirm=1&token_hash=hash-1'))).rejects.toThrow('identity changed');
  });
  it('strips auth return parameters from the URL without touching other state', () => {
    const cleaned = stripAdditionalEmailReturn(new URL('https://app.example/?additional_confirm=1&token_hash=h&type=email&foo=bar#access_token=at&refresh_token=rt&token_type=bearer&section=servers'));
    const url = new URL(cleaned);
    expect(url.searchParams.get('additional_confirm')).toBeNull();
    expect(url.searchParams.get('token_hash')).toBeNull();
    expect(url.searchParams.get('type')).toBeNull();
    expect(url.searchParams.get('foo')).toBe('bar');
    expect(url.hash).toBe('#section=servers');
    expect(stripAdditionalEmailReturn(new URL('https://app.example/?additional_confirm=1#access_token=at&refresh_token=rt'))).toBe('https://app.example/');
  });
});