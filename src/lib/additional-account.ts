import { createClient, type EmailOtpType, type SupabaseClient } from '@supabase/supabase-js';
import { additionalBackend } from './additional-backend';

/** Fail closed: an automatically confirmed signup is not mailbox ownership proof. */
export async function checkAdditionalEmailVerification() {
  const response = await fetch(`${additionalBackend.url}/auth/v1/settings`, {
    headers: { apikey: additionalBackend.publishableKey },
    cache: 'no-store',
  });
  if (!response.ok) throw new Error('Additional email settings could not be checked.');
  const settings = await response.json();
  if (settings.external?.email !== true || settings.mailer_autoconfirm !== false || settings.disable_signup !== false) {
    throw new Error('Additional email confirmation must be enabled before verification can continue.');
  }
}

/** Only a successful mailbox challenge followed by a matching live identity is proof. */
export async function confirmAdditionalEmailCode(client: SupabaseClient, email: string, code: string) {
  await checkAdditionalEmailVerification();
  const { data, error } = await client.auth.verifyOtp({ email: email.trim(), token: code.trim(), type: 'email' });
  if (error || !data.user || !data.session) throw new Error('Email confirmation failed.');
  const account = await verifyAdditionalAccount(client);
  if (account.id !== data.user.id) throw new Error('Account identity changed during verification.');
  return account;
}

/** Authentication only: never grants feature access or accepts a Cloud session. */
export function createAdditionalAccountClient() {
  return createClient(additionalBackend.url, additionalBackend.publishableKey, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false, storageKey: 'tunnel-external-account-verification' },
    global: {
      fetch: (input, init) => {
        const url = new URL(input instanceof Request ? input.url : String(input));
        if (url.origin !== additionalBackend.url || !url.pathname.startsWith('/auth/v1/')) {
          return Promise.reject(new Error('Private features remain locked until permissions are verified.'));
        }
        const headers = new Headers(input instanceof Request ? input.headers : undefined);
        new Headers(init?.headers).forEach((value, key) => headers.set(key, value));
        if (headers.get('Authorization') === `Bearer ${additionalBackend.publishableKey}`) headers.delete('Authorization');
        headers.set('apikey', additionalBackend.publishableKey);
        return fetch(input, { ...init, headers });
      },
    },
  });
}

/** getUser makes a live request; local session state or an email match is not proof. */
export async function verifyAdditionalAccount(client: SupabaseClient) {
  const { data, error } = await client.auth.getUser();
  if (error || !data.user || !data.user.email_confirmed_at || data.user.is_anonymous) {
    throw new Error('Confirm your additional account email before continuing.');
  }
  return { id: data.user.id, email: data.user.email ?? '' };
}

/**
 * Email-link confirmation return.
 *
 * The additional backend's hosted email template sends a confirmation LINK,
 * not a numeric code, so signInWithOtp points its redirect back at the app
 * origin carrying this marker. The marker lives in the query string (not the
 * fragment) because Supabase's implicit-flow redirect rewrites the fragment
 * with session tokens, which would clobber a fragment marker; the query
 * marker survives every return shape.
 */
export const ADDITIONAL_CONFIRM_MARKER = 'additional_confirm';

/** Where the confirmation email link lands: the app origin plus the marker. */
export function additionalEmailRedirectTo(origin: string) {
  return `${origin}/?${ADDITIONAL_CONFIRM_MARKER}=1`;
}

/** True only for URLs that claim to be an additional-backend email return. */
export function hasAdditionalEmailReturnMarker(url: URL) {
  return url.searchParams.get(ADDITIONAL_CONFIRM_MARKER) === '1';
}

export type AdditionalEmailReturn =
  | { kind: 'token_hash'; tokenHash: string; type: EmailOtpType }
  | { kind: 'session'; accessToken: string; refreshToken: string }
  | { kind: 'none' };

const EMAIL_RETURN_TYPES: readonly EmailOtpType[] = ['email', 'magiclink', 'signup', 'email_change', 'recovery', 'invite'];

/**
 * Parse a Supabase auth return for the additional backend. Returns 'none'
 * unless the URL carries the additional-confirm marker AND usable
 * credentials: a token_hash (+ type) in the query, or a full session
 * (access_token + refresh_token) in the fragment. A marker alone is not
 * proof of anything; the gate in completeAdditionalEmailReturn decides.
 */
export function parseAdditionalEmailReturn(url: URL): AdditionalEmailReturn {
  if (!hasAdditionalEmailReturnMarker(url)) return { kind: 'none' };
  const tokenHash = url.searchParams.get('token_hash');
  if (tokenHash) {
    const rawType = url.searchParams.get('type');
    const type = EMAIL_RETURN_TYPES.includes(rawType as EmailOtpType) ? (rawType as EmailOtpType) : 'email';
    return { kind: 'token_hash', tokenHash, type };
  }
  const hashParams = new URLSearchParams(url.hash.startsWith('#') ? url.hash.slice(1) : url.hash);
  const accessToken = hashParams.get('access_token');
  const refreshToken = hashParams.get('refresh_token');
  if (accessToken && refreshToken) return { kind: 'session', accessToken, refreshToken };
  return { kind: 'none' };
}

const RETURN_SEARCH_PARAMS = [ADDITIONAL_CONFIRM_MARKER, 'token_hash', 'type', 'code', 'error', 'error_code', 'error_description'];
const RETURN_HASH_PARAMS = ['access_token', 'refresh_token', 'expires_in', 'expires_at', 'token_type', 'type', 'provider_token', 'provider_refresh_token'];

/** The URL with every auth-return parameter removed, so a refresh cannot replay it. Other state is preserved. */
export function stripAdditionalEmailReturn(url: URL): string {
  const cleaned = new URL(url.toString());
  for (const key of RETURN_SEARCH_PARAMS) cleaned.searchParams.delete(key);
  if (cleaned.hash.length > 1) {
    const hashParams = new URLSearchParams(cleaned.hash.slice(1));
    for (const key of RETURN_HASH_PARAMS) hashParams.delete(key);
    const rest = hashParams.toString();
    cleaned.hash = rest ? `#${rest}` : '';
  }
  return cleaned.toString();
}

/**
 * Complete verification from an email-link return, against the additional
 * backend only: verify the token_hash (preferred) or adopt the fragment
 * session into the isolated additional client, then apply exactly the same
 * fail-closed settings check and live-identity gate as the code path.
 * Returns null when the URL is not an additional-backend return.
 */
export async function completeAdditionalEmailReturn(client: SupabaseClient, url: URL) {
  const ret = parseAdditionalEmailReturn(url);
  if (ret.kind === 'none') return null;
  await checkAdditionalEmailVerification();
  let verifiedUserId: string | null = null;
  if (ret.kind === 'token_hash') {
    const { data, error } = await client.auth.verifyOtp({ token_hash: ret.tokenHash, type: ret.type });
    if (error || !data.session) throw new Error('Email confirmation failed.');
    verifiedUserId = data.user?.id ?? null;
  } else {
    const { data, error } = await client.auth.setSession({ access_token: ret.accessToken, refresh_token: ret.refreshToken });
    if (error || !data.session) throw new Error('Email confirmation failed.');
    verifiedUserId = data.user?.id ?? null;
  }
  const account = await verifyAdditionalAccount(client);
  if (verifiedUserId && account.id !== verifiedUserId) throw new Error('Account identity changed during verification.');
  return account;
}