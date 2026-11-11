import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { additionalBackend } from './additional-backend';
import { verifyAdditionalAccount } from './additional-account';

/** The private bucket created for the additional backend (docs/additional-backend-storage.sql). */
export const USER_FILES_BUCKET = 'user-files';

/** Bucket file_size_limit from docs/additional-backend-storage.sql (10 MB). */
export const USER_FILES_MAX_BYTES = 10485760;

const OBJECT_PREFIX = `/storage/v1/object/${USER_FILES_BUCKET}/`;
const LIST_PATH = `/storage/v1/object/list/${USER_FILES_BUCKET}`;
const REMOVE_PATH = `/storage/v1/object/${USER_FILES_BUCKET}`;
const OBJECT_METHODS: ReadonlySet<string> = new Set(['GET', 'HEAD', 'POST', 'PUT', 'DELETE']);

/**
 * Isolated in-memory client for the owner's private files only. Mirrors the
 * monitoring client's session handling, but its request guard allows
 * exactly two destinations on the additional backend: Auth (to hold the
 * verified session) and Storage object paths inside the user-files bucket
 * (list, upload/update, download, remove). Bucket administration and every
 * other bucket stay locked, and no Cloud session or managed client is ever
 * involved.
 */
export function createAdditionalFilesClient() {
  return createClient(additionalBackend.url, additionalBackend.publishableKey, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false, storageKey: 'tunnel-external-files' },
    global: {
      fetch: (input, init) => {
        const url = new URL(input instanceof Request ? input.url : String(input));
        const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
        const isAuth = url.pathname.startsWith('/auth/v1/');
        const isList = url.pathname === LIST_PATH && method === 'POST';
        const isRemove = url.pathname === REMOVE_PATH && method === 'DELETE';
        const isObject = url.pathname.startsWith(OBJECT_PREFIX) && url.pathname.length > OBJECT_PREFIX.length && OBJECT_METHODS.has(method);
        if (url.origin !== additionalBackend.url || (!isAuth && !isList && !isRemove && !isObject)) {
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
 * The content type the bucket accepts for a file name, or null when the
 * bucket's allowed_mime_types (text/plain, application/pdf) reject it.
 */
export function userFileContentType(name: string): 'text/plain' | 'application/pdf' | null {
  const lower = name.trim().toLowerCase();
  if (lower.endsWith('.txt')) return 'text/plain';
  if (lower.endsWith('.pdf')) return 'application/pdf';
  return null;
}

/**
 * A bare file name safe to place under the owner's storage prefix, or null.
 * Slashes and traversal are rejected: the name must stay a single segment so
 * the object always lands at <user_id>/<name>, where the storage policies
 * (first folder segment = auth.uid()) can see it as the owner's.
 */
export function sanitizeUserFileName(name: string): string | null {
  const clean = name.trim();
  if (!clean || clean === '.' || clean === '..' || clean.includes('/') || clean.includes('\\')) return null;
  return clean;
}

/** The storage object path the policies reserve for this owner. */
export function userFilePath(userId: string, name: string): string {
  return `${userId}/${name}`;
}

/** One file in the owner's private prefix of the user-files bucket. */
export type UserFileEntry = {
  name: string;
  size: number | null;
  created_at: string | null;
  updated_at: string | null;
};

/** Owner gate first, then the files under the owner's own prefix, newest first. */
export async function listFiles(client: SupabaseClient): Promise<UserFileEntry[]> {
  const account = await verifyAdditionalAccount(client);
  const { data, error } = await client.storage
    .from(USER_FILES_BUCKET)
    .list(account.id, { limit: 100, sortBy: { column: 'created_at', order: 'desc' } });
  if (error || !data) throw new Error('Files could not be loaded.');
  return data
    .filter((entry) => entry.id !== null)
    .map((entry) => ({
      name: entry.name,
      size: typeof entry.metadata?.size === 'number' ? entry.metadata.size : null,
      created_at: entry.created_at ?? null,
      updated_at: entry.updated_at ?? null,
    }));
}

/**
 * Stores one text or PDF file under the owner's prefix. Name, type and size
 * are checked against the bucket policy client-side first (text/plain or
 * application/pdf, non-empty, at most 10 MB); the bucket enforces the same
 * rules server-side. Uploading an existing name replaces it (upsert).
 */
export async function uploadFile(client: SupabaseClient, name: string, content: Blob | string): Promise<UserFileEntry> {
  const clean = sanitizeUserFileName(name);
  if (!clean) throw new Error('That file name cannot be stored.');
  const contentType = userFileContentType(clean);
  if (!contentType) throw new Error('Only text (.txt) and PDF (.pdf) files can be stored.');
  const body = typeof content === 'string' ? new Blob([content], { type: contentType }) : content;
  if (body.size === 0) throw new Error('Empty files cannot be stored.');
  if (body.size > USER_FILES_MAX_BYTES) throw new Error('File is larger than the 10 MB storage limit.');
  const account = await verifyAdditionalAccount(client);
  const { error } = await client.storage
    .from(USER_FILES_BUCKET)
    .upload(userFilePath(account.id, clean), body, { contentType, upsert: true });
  if (error) throw new Error('File could not be uploaded.');
  return { name: clean, size: body.size, created_at: null, updated_at: null };
}

/** Owner gate first, then download one of the owner's files as a Blob. */
export async function downloadFile(client: SupabaseClient, name: string): Promise<Blob> {
  const clean = sanitizeUserFileName(name);
  if (!clean) throw new Error('That file name cannot be stored.');
  const account = await verifyAdditionalAccount(client);
  const { data, error } = await client.storage.from(USER_FILES_BUCKET).download(userFilePath(account.id, clean));
  if (error || !data) throw new Error('File could not be downloaded.');
  return data;
}

/** Owner gate first, then delete one of the owner's files. */
export async function deleteFile(client: SupabaseClient, name: string): Promise<void> {
  const clean = sanitizeUserFileName(name);
  if (!clean) throw new Error('That file name cannot be stored.');
  const account = await verifyAdditionalAccount(client);
  const { error } = await client.storage.from(USER_FILES_BUCKET).remove([userFilePath(account.id, clean)]);
  if (error) throw new Error('File could not be deleted.');
}
