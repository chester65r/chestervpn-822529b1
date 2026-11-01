import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { additionalBackend } from './additional-backend';

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