import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { additionalBackend } from './additional-backend';
import { verifyAdditionalAccount } from './additional-account';

/** One collector-written row of public.tunnel_server_health (additional backend). */
export type ServerHealthRow = {
  id: string;
  server_id: string;
  status: 'healthy' | 'unreachable' | 'degraded';
  latency_ms: number | null;
  observed_at: string;
};

/** The most recent collector report for one server. */
export type LatestServerHealth = ServerHealthRow;

const HEALTH_TABLE_PATH = '/rest/v1/tunnel_server_health';

/** Pure reduction: newest report per server, newest first. Input order is irrelevant. */
export function latestHealthByServer(rows: readonly ServerHealthRow[]): LatestServerHealth[] {
  const latest = new Map<string, ServerHealthRow>();
  for (const row of rows) {
    const current = latest.get(row.server_id);
    if (!current || Date.parse(row.observed_at) > Date.parse(current.observed_at)) {
      latest.set(row.server_id, row);
    }
  }
  return [...latest.values()].sort((a, b) => Date.parse(b.observed_at) - Date.parse(a.observed_at));
}

/**
 * Isolated in-memory client for monitoring reads only. Mirrors the additional
 * account client's session handling, but its request guard allows exactly two
 * destinations on the additional backend: Auth (to hold the verified session)
 * and GET/HEAD reads of tunnel_server_health. Every other path — including
 * writes to the health table itself — stays locked, and no Cloud session or
 * managed client is ever involved.
 */
export function createAdditionalHealthClient() {
  return createClient(additionalBackend.url, additionalBackend.publishableKey, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false, storageKey: 'tunnel-external-health-read' },
    global: {
      fetch: (input, init) => {
        const url = new URL(input instanceof Request ? input.url : String(input));
        const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
        const isAuth = url.pathname.startsWith('/auth/v1/');
        const isHealthRead = url.pathname === HEALTH_TABLE_PATH && (method === 'GET' || method === 'HEAD');
        if (url.origin !== additionalBackend.url || (!isAuth && !isHealthRead)) {
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

/** Move the just-verified additional-account session into the health client. */
export async function adoptVerifiedSession(healthClient: SupabaseClient, accountClient: SupabaseClient) {
  const { data, error } = await accountClient.auth.getSession();
  if (error || !data.session) throw new Error('Verify your additional account before loading monitoring.');
  const { error: adoptError } = await healthClient.auth.setSession({
    access_token: data.session.access_token,
    refresh_token: data.session.refresh_token,
  });
  if (adoptError) throw new Error('Monitoring session could not be established.');
}

/**
 * Owner-confirmed gate first (live getUser: confirmed, non-anonymous identity),
 * then the owner's health rows; RLS on the additional backend enforces the same
 * ownership server-side. Returns the latest report per server, or an empty
 * array when the collector has not posted any report yet.
 */
export async function fetchLatestServerHealth(client: SupabaseClient): Promise<LatestServerHealth[]> {
  const account = await verifyAdditionalAccount(client);
  const { data, error } = await client
    .from('tunnel_server_health')
    .select('id,server_id,status,latency_ms,observed_at')
    .eq('user_id', account.id)
    .order('observed_at', { ascending: false })
    .limit(200);
  if (error || !data) throw new Error('Server health could not be loaded.');
  return latestHealthByServer(data as ServerHealthRow[]);
}
