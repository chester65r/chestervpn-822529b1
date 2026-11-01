import { createClient, type SupabaseClient } from '@supabase/supabase-js';
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