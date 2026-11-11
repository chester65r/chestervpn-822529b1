import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { additionalBackend } from './additional-backend';
import { verifyAdditionalAccount } from './additional-account';

/** The only source values public.tunnel_connection_history accepts (check constraint in docs/additional-backend-permissions.sql). */
export type ConnectionSource = 'simulation' | 'native';

/** The only status values public.tunnel_connection_history accepts. */
export type ConnectionStatus = 'connected' | 'disconnected' | 'error';

/** The only error_category values public.tunnel_connection_history accepts. */
export type ConnectionErrorCategory = 'validation' | 'network' | 'authentication' | 'timeout' | 'unknown';

/** One row of public.tunnel_connection_history (additional backend). */
export type ConnectionHistoryRow = {
  id: string;
  source: ConnectionSource;
  status: ConnectionStatus;
  error_category: ConnectionErrorCategory | null;
  occurred_at: string;
};

/** A real connection event to record; errorCategory is only meaningful for status 'error'. */
export type ConnectionEvent = {
  source: ConnectionSource;
  status: ConnectionStatus;
  errorCategory?: ConnectionErrorCategory;
};

const HISTORY_TABLE_PATH = '/rest/v1/tunnel_connection_history';
const HISTORY_METHODS: ReadonlySet<string> = new Set(['GET', 'HEAD', 'POST', 'DELETE']);
const CONNECTION_SOURCES: readonly ConnectionSource[] = ['simulation', 'native'];
const CONNECTION_STATUSES: readonly ConnectionStatus[] = ['connected', 'disconnected', 'error'];
const ERROR_CATEGORIES: readonly ConnectionErrorCategory[] = ['validation', 'network', 'authentication', 'timeout', 'unknown'];

/**
 * Isolated in-memory client for connection history only. Mirrors the
 * monitoring client's session handling, but its request guard allows
 * exactly two destinations on the additional backend: Auth (to hold the
 * verified session) and tunnel_connection_history with the methods the
 * table grants to authenticated owners (no UPDATE grant exists, so PATCH
 * stays locked). Every other path stays locked, and no Cloud session or
 * managed client is ever involved.
 */
export function createAdditionalHistoryClient() {
  return createClient(additionalBackend.url, additionalBackend.publishableKey, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false, storageKey: 'tunnel-external-history' },
    global: {
      fetch: (input, init) => {
        const url = new URL(input instanceof Request ? input.url : String(input));
        const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
        const isAuth = url.pathname.startsWith('/auth/v1/');
        const isHistory = url.pathname === HISTORY_TABLE_PATH && HISTORY_METHODS.has(method);
        if (url.origin !== additionalBackend.url || (!isAuth && !isHistory)) {
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

/** Owner gate first, then the owner's connection history, latest first. */
export async function listHistory(client: SupabaseClient, limit = 20): Promise<ConnectionHistoryRow[]> {
  const account = await verifyAdditionalAccount(client);
  const bounded = Math.min(100, Math.max(1, Math.floor(limit) || 20));
  const { data, error } = await client
    .from('tunnel_connection_history')
    .select('id,source,status,error_category,occurred_at')
    .eq('user_id', account.id)
    .order('occurred_at', { ascending: false })
    .limit(bounded);
  if (error || !data) throw new Error('Connection history could not be loaded.');
  return data as ConnectionHistoryRow[];
}

/**
 * Records one real connection event for the verified owner. occurred_at is
 * omitted on purpose: the database default stamps it, so a client clock
 * cannot forge event times. error_category is stored only for 'error'
 * events (defaulting to 'unknown'); for any other status it stays null,
 * matching what the column means.
 */
export async function recordHistory(client: SupabaseClient, event: ConnectionEvent): Promise<ConnectionHistoryRow> {
  if (!CONNECTION_SOURCES.includes(event.source) || !CONNECTION_STATUSES.includes(event.status)) {
    throw new Error('Connection event could not be recorded.');
  }
  if (event.errorCategory !== undefined && !ERROR_CATEGORIES.includes(event.errorCategory)) {
    throw new Error('Connection event could not be recorded.');
  }
  await verifyAdditionalAccount(client);
  const error_category: ConnectionErrorCategory | null =
    event.status === 'error' ? (event.errorCategory ?? 'unknown') : null;
  const { data, error } = await client
    .from('tunnel_connection_history')
    .insert({ source: event.source, status: event.status, error_category })
    .select('id,source,status,error_category,occurred_at')
    .single();
  if (error || !data) throw new Error('Connection event could not be recorded.');
  return data as ConnectionHistoryRow;
}
