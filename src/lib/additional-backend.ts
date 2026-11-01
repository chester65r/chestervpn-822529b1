import { createClient } from '@supabase/supabase-js';

/** Public configuration only. Never reuse the Cloud session or managed client. */
export const additionalBackend = {
  name: 'bolt-native-database-72536136',
  url: 'https://acggyexxumhswmyfdiiy.supabase.co',
  publishableKey: 'sb_publishable_X16bOCsN8_lPfxefwyTeIA_k8KwIO6G',
} as const;

/** An isolated, anonymous read connection; private feature access is not enabled. */
export function createAdditionalBackendReader() {
  return createClient(additionalBackend.url, additionalBackend.publishableKey, {
    auth: {
      persistSession: false,
      autoRefreshToken: false,
      detectSessionInUrl: false,
      storageKey: 'tunnel-additional-backend-readonly',
    },
    global: {
      fetch: (input, init) => {
        const headers = new Headers(init?.headers);
        // Never send the opaque public key as a JWT bearer.
        headers.delete('Authorization');
        headers.set('apikey', additionalBackend.publishableKey);
        // This connection cannot mutate data or authenticate accounts.
        const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
        if (method !== 'GET' && method !== 'HEAD') {
          return Promise.reject(new Error('Additional backend connection is read-only.'));
        }
        return fetch(input, { ...init, headers });
      },
    },
  });
}