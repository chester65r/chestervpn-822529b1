import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { additionalBackend } from './additional-backend';
import { verifyAdditionalAccount } from './additional-account';
import { parseSavedProfiles, ROUTE_PRESETS, SAVED_PROFILES_KEY, type SavedProfile } from './tunnel';

/** The only preset values public.tunnel_profiles accepts (check constraint in docs/additional-backend-permissions.sql). */
export type RemoteProfilePreset = 'custom' | 'standard-tls' | 'websocket';

/** One row of public.tunnel_profiles (additional backend). */
export type RemoteProfile = {
  id: string;
  preset: RemoteProfilePreset;
  port: number;
  created_at: string;
};

/** The writable columns of public.tunnel_profiles; user_id defaults to auth.uid() server-side. */
export type RemoteProfileInput = {
  preset: RemoteProfilePreset;
  port: number;
};

const PROFILES_TABLE_PATH = '/rest/v1/tunnel_profiles';
const PROFILES_METHODS: ReadonlySet<string> = new Set(['GET', 'HEAD', 'POST', 'PATCH', 'DELETE']);
const REMOTE_PRESETS: readonly RemoteProfilePreset[] = ['custom', 'standard-tls', 'websocket'];

/**
 * Isolated in-memory client for profile sync only. Mirrors the monitoring
 * client's session handling, but its request guard allows exactly two
 * destinations on the additional backend: Auth (to hold the verified
 * session) and tunnel_profiles with the methods the table grants to
 * authenticated owners. Every other path stays locked, and no Cloud session
 * or managed client is ever involved.
 */
export function createAdditionalProfilesClient() {
  return createClient(additionalBackend.url, additionalBackend.publishableKey, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false, storageKey: 'tunnel-external-profiles' },
    global: {
      fetch: (input, init) => {
        const url = new URL(input instanceof Request ? input.url : String(input));
        const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
        const isAuth = url.pathname.startsWith('/auth/v1/');
        const isProfiles = url.pathname === PROFILES_TABLE_PATH && PROFILES_METHODS.has(method);
        if (url.origin !== additionalBackend.url || (!isAuth && !isProfiles)) {
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

/**
 * Maps one locally saved profile to the columns tunnel_profiles can store.
 * The local store (SavedProfile) carries name/host/SNI/payload, but the
 * additional-backend table holds only preset + port, so that pair is the
 * whole synced representation — nothing else is invented or implied.
 * Local 'custom' maps to 'custom'; the named route presets (alpha/beta/
 * gamma) are all TLS SNI routing presets, so they map to 'standard-tls';
 * an unrecognized presetId falls back to 'custom', matching parseActiveProfile.
 * Returns null when the local port is not a valid 1-65535 integer.
 */
export function toRemoteProfile(saved: SavedProfile): RemoteProfileInput | null {
  const rawPort = saved.profile.port.trim();
  if (!/^\d+$/.test(rawPort)) return null;
  const port = Number(rawPort);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  const isNamedPreset = ROUTE_PRESETS.some((preset) => preset.id === saved.presetId && preset.id !== 'custom');
  const preset: RemoteProfilePreset = saved.presetId === 'custom' ? 'custom' : isNamedPreset ? 'standard-tls' : 'custom';
  return { preset, port };
}

/** The app's real locally saved profiles, restored exactly as the workspace restores them. */
export function readLocalSavedProfiles(): SavedProfile[] {
  try {
    return parseSavedProfiles(window.localStorage.getItem(SAVED_PROFILES_KEY));
  } catch {
    return [];
  }
}

function isRemoteProfileInput(input: RemoteProfileInput): boolean {
  return REMOTE_PRESETS.includes(input.preset) && Number.isInteger(input.port) && input.port >= 1 && input.port <= 65535;
}

/** Owner gate first, then the owner's synced profiles, newest first. */
export async function listProfiles(client: SupabaseClient): Promise<RemoteProfile[]> {
  const account = await verifyAdditionalAccount(client);
  const { data, error } = await client
    .from('tunnel_profiles')
    .select('id,preset,port,created_at')
    .eq('user_id', account.id)
    .order('created_at', { ascending: false })
    .limit(200);
  if (error || !data) throw new Error('Synced profiles could not be loaded.');
  return data as RemoteProfile[];
}

/**
 * Writes one profile representation to the additional backend. The table
 * has no unique constraint to conflict on, so this is a plain insert of the
 * table's writable columns; syncProfiles dedupes against listProfiles first.
 */
export async function upsertProfile(client: SupabaseClient, input: RemoteProfileInput): Promise<RemoteProfile> {
  if (!isRemoteProfileInput(input)) throw new Error('Profile could not be saved to the additional backend.');
  await verifyAdditionalAccount(client);
  const { data, error } = await client
    .from('tunnel_profiles')
    .insert({ preset: input.preset, port: input.port })
    .select('id,preset,port,created_at')
    .single();
  if (error || !data) throw new Error('Profile could not be saved to the additional backend.');
  return data as RemoteProfile;
}

/** Owner gate first, then remove one of the owner's synced profiles by id. */
export async function deleteProfile(client: SupabaseClient, id: string): Promise<void> {
  const account = await verifyAdditionalAccount(client);
  const { error } = await client.from('tunnel_profiles').delete().eq('id', id).eq('user_id', account.id);
  if (error) throw new Error('Profile could not be removed from the additional backend.');
}

export type ProfileSyncResult = {
  uploaded: number;
  alreadyPresent: number;
  skipped: number;
  profiles: RemoteProfile[];
};

/**
 * Pushes the local saved profiles' synced representations up: mappable
 * profiles whose (preset, port) pair is not already stored are inserted,
 * the rest are counted honestly. Returns the post-sync remote list.
 */
export async function syncProfiles(client: SupabaseClient, local: readonly SavedProfile[]): Promise<ProfileSyncResult> {
  const before = await listProfiles(client);
  const present = new Set(before.map((row) => `${row.preset}:${row.port}`));
  let uploaded = 0;
  let alreadyPresent = 0;
  let skipped = 0;
  for (const saved of local) {
    const mapped = toRemoteProfile(saved);
    if (!mapped) {
      skipped += 1;
      continue;
    }
    const key = `${mapped.preset}:${mapped.port}`;
    if (present.has(key)) {
      alreadyPresent += 1;
      continue;
    }
    await upsertProfile(client, mapped);
    present.add(key);
    uploaded += 1;
  }
  const profiles = await listProfiles(client);
  return { uploaded, alreadyPresent, skipped, profiles };
}
