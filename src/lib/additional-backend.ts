import { createClient } from '@supabase/supabase-js';

/**
 * Public configuration only. Never reuse the Cloud session or managed client.
 * Points at the owner's additional-backend project "supabase-canary-park"
 * (ref ezqzcvotfwdfthwuxrsy) — the project whose tunnel_* tables, owner
 * function, RLS policies and private user-files bucket were applied and
 * verified on 2026-11-11. The legacy bolt-native project previously named
 * here is not in the owner's Supabase org and must not be used.
 */
export const additionalBackend = {
  name: 'supabase-canary-park',
  url: 'https://ezqzcvotfwdfthwuxrsy.supabase.co',
  // TODO(owner): paste canary-park's Publishable key here (Supabase dashboard
  // → supabase-canary-park → Project Settings → API Keys → Publishable). It is
  // a public client key, safe to ship in the app. Until it is replaced, every
  // additional-backend request fails closed (401) and verification stays
  // paused — no data can leak to the wrong project.
  publishableKey: 'sb_publishable_0ZIhZqQsr6voEylKAoeLgA_3xI-NGXc',
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