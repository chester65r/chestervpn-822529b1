import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SupabaseClient } from '@supabase/supabase-js';
import {
  createAdditionalFilesClient,
  deleteFile,
  downloadFile,
  listFiles,
  sanitizeUserFileName,
  uploadFile,
  userFileContentType,
  userFilePath,
  USER_FILES_MAX_BYTES,
} from '../lib/additional-files';
import { additionalBackend } from '../lib/additional-backend';

afterEach(() => vi.unstubAllGlobals());

/** jsdom Blobs have no .text(); FileReader is the portable read in this environment. */
function blobText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

describe('file policy helpers', () => {
  it('accepts only the bucket mime types, by extension', () => {
    expect(userFileContentType('notes.txt')).toBe('text/plain');
    expect(userFileContentType('REPORT.PDF')).toBe('application/pdf');
    expect(userFileContentType('photo.png')).toBeNull();
    expect(userFileContentType('archive.zip')).toBeNull();
    expect(userFileContentType('no-extension')).toBeNull();
  });
  it('keeps names a single segment under the owner prefix', () => {
    expect(sanitizeUserFileName('  notes.txt ')).toBe('notes.txt');
    expect(sanitizeUserFileName('my file.pdf')).toBe('my file.pdf');
    expect(sanitizeUserFileName('')).toBeNull();
    expect(sanitizeUserFileName('..')).toBeNull();
    expect(sanitizeUserFileName('../secret.txt')).toBeNull();
    expect(sanitizeUserFileName('folder/notes.txt')).toBeNull();
    expect(sanitizeUserFileName('folder\\notes.txt')).toBeNull();
    expect(userFilePath('owner', 'notes.txt')).toBe('owner/notes.txt');
  });
});

const confirmedOwner = {
  data: { user: { id: 'owner', email: 'owner@example.com', email_confirmed_at: '2026-11-01', is_anonymous: false } },
  error: null,
};

function storageFake(entries: unknown[] = []) {
  const list = vi.fn().mockResolvedValue({ data: entries, error: null });
  const upload = vi.fn().mockResolvedValue({ data: { path: 'owner/x' }, error: null });
  const download = vi.fn().mockResolvedValue({ data: new Blob(['contents']), error: null });
  const remove = vi.fn().mockResolvedValue({ data: [], error: null });
  const from = vi.fn(() => ({ list, upload, download, remove }));
  return { from, list, upload, download, remove };
}

describe('owner-confirmed file operations', () => {
  it('rejects an unconfirmed identity before storage is touched', async () => {
    const storage = storageFake();
    const client = { auth: { getUser: vi.fn().mockResolvedValue({ data: { user: { id: 'external', email_confirmed_at: null } }, error: null }) }, storage } as unknown as SupabaseClient;
    await expect(listFiles(client)).rejects.toThrow('Confirm');
    await expect(uploadFile(client, 'notes.txt', 'hello')).rejects.toThrow('Confirm');
    await expect(downloadFile(client, 'notes.txt')).rejects.toThrow('Confirm');
    await expect(deleteFile(client, 'notes.txt')).rejects.toThrow('Confirm');
    expect(storage.from).not.toHaveBeenCalled();
  });
  it('lists only the owner prefix, newest first, mapping file metadata and skipping folders', async () => {
    const storage = storageFake([
      { id: 'f1', name: 'notes.txt', created_at: '2026-11-11T02:00:00Z', updated_at: '2026-11-11T02:30:00Z', metadata: { size: 128 } },
      { id: null, name: 'a-folder', created_at: '2026-11-11T01:00:00Z', updated_at: null, metadata: null },
    ]);
    const client = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, storage } as unknown as SupabaseClient;
    const files = await listFiles(client);
    expect(storage.from).toHaveBeenCalledWith('user-files');
    expect(storage.list).toHaveBeenCalledWith('owner', { limit: 100, sortBy: { column: 'created_at', order: 'desc' } });
    expect(files).toEqual([{ name: 'notes.txt', size: 128, created_at: '2026-11-11T02:00:00Z', updated_at: '2026-11-11T02:30:00Z' }]);
  });
  it('uploads under the owner prefix with the bucket content type', async () => {
    const storage = storageFake();
    const client = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, storage } as unknown as SupabaseClient;
    const stored = await uploadFile(client, 'report.pdf', new Blob(['%PDF'], { type: 'application/pdf' }));
    expect(storage.upload).toHaveBeenCalledWith('owner/report.pdf', expect.any(Blob), { contentType: 'application/pdf', upsert: true });
    expect(stored).toEqual({ name: 'report.pdf', size: 4, created_at: null, updated_at: null });
    await uploadFile(client, 'notes.txt', 'hello');
    expect(storage.upload).toHaveBeenCalledWith('owner/notes.txt', expect.any(Blob), { contentType: 'text/plain', upsert: true });
  });
  it('validates name, type and size before any storage or auth call', async () => {
    const storage = storageFake();
    const getUser = vi.fn().mockResolvedValue(confirmedOwner);
    const client = { auth: { getUser }, storage } as unknown as SupabaseClient;
    await expect(uploadFile(client, 'photo.png', new Blob(['x']))).rejects.toThrow('Only text (.txt) and PDF (.pdf)');
    await expect(uploadFile(client, '../notes.txt', 'x')).rejects.toThrow('cannot be stored');
    await expect(uploadFile(client, 'notes.txt', '')).rejects.toThrow('Empty files');
    const oversized = new Blob(['x']);
    Object.defineProperty(oversized, 'size', { value: USER_FILES_MAX_BYTES + 1 });
    await expect(uploadFile(client, 'notes.txt', oversized)).rejects.toThrow('10 MB');
    await expect(downloadFile(client, 'a/b.txt')).rejects.toThrow('cannot be stored');
    await expect(deleteFile(client, '..')).rejects.toThrow('cannot be stored');
    expect(storage.from).not.toHaveBeenCalled();
    expect(getUser).not.toHaveBeenCalled();
  });
  it('downloads and deletes under the owner prefix', async () => {
    const storage = storageFake();
    const client = { auth: { getUser: vi.fn().mockResolvedValue(confirmedOwner) }, storage } as unknown as SupabaseClient;
    const blob = await downloadFile(client, 'notes.txt');
    expect(storage.download).toHaveBeenCalledWith('owner/notes.txt');
    await expect(blobText(blob)).resolves.toBe('contents');
    await deleteFile(client, 'notes.txt');
    expect(storage.remove).toHaveBeenCalledWith(['owner/notes.txt']);
  });
});

describe('files client request guard', () => {
  it('blocks other buckets and bucket administration before any request is made', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const client = createAdditionalFilesClient();
    const other = await client.storage.from('avatars').upload('owner/x.txt', new Blob(['x']));
    expect(other.error?.message).toContain('locked');
    const signed = await client.storage.from('user-files').createSignedUrl('owner/x.txt', 60);
    expect(signed.error?.message).toContain('locked');
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe('files client live request shapes', () => {
  const b64 = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const fakeJwt = `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ sub: 'owner', exp: 4102444800 })}.sig`;
  const ownerJson = { id: 'owner', email: 'owner@example.com', email_confirmed_at: '2026-11-01', is_anonymous: false, aud: 'authenticated', role: 'authenticated' };

  function routedClient(storageHandler: (url: URL, init?: RequestInit) => Response) {
    const fetch = vi.fn(async (input: unknown, init?: RequestInit) => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      if (url.pathname === '/auth/v1/user') {
        return new Response(JSON.stringify(ownerJson), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      if (url.pathname.startsWith('/auth/v1/token')) {
        return new Response(JSON.stringify({ access_token: fakeJwt, refresh_token: 'rt-2', token_type: 'bearer', expires_in: 3600, user: ownerJson }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      return storageHandler(url, init);
    });
    vi.stubGlobal('fetch', fetch);
    return { client: createAdditionalFilesClient(), fetch };
  }

  async function signedInClient(storageHandler: (url: URL, init?: RequestInit) => Response) {
    const { client, fetch } = routedClient(storageHandler);
    await client.auth.setSession({ access_token: fakeJwt, refresh_token: 'rt-1' });
    return { client, fetch };
  }

  function storageCalls(fetch: ReturnType<typeof vi.fn>) {
    return fetch.mock.calls.filter(([input]) => String(input instanceof Request ? input.url : input).includes('/storage/v1/'));
  }

  it('lists through the storage list endpoint scoped to the owner prefix', async () => {
    const { client, fetch } = await signedInClient(() => new Response(JSON.stringify([
      { id: 'f1', name: 'notes.txt', created_at: '2026-11-11T02:00:00Z', updated_at: null, metadata: { size: 5 } },
    ]), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const files = await listFiles(client);
    expect(files).toEqual([{ name: 'notes.txt', size: 5, created_at: '2026-11-11T02:00:00Z', updated_at: null }]);
    const calls = storageCalls(fetch);
    expect(calls).toHaveLength(1);
    const [input, init] = calls[0] as [unknown, RequestInit];
    expect(String(input instanceof Request ? input.url : input)).toBe(`${additionalBackend.url}/storage/v1/object/list/user-files`);
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body)).prefix).toBe('owner');
  });
  it('uploads, downloads and removes through the owner object paths', async () => {
    const { client, fetch } = await signedInClient((url, init) => {
      if ((init?.method ?? 'GET') === 'GET' && url.pathname.endsWith('/owner/notes.txt')) {
        return new Response('hello', { status: 200, headers: { 'Content-Type': 'text/plain' } });
      }
      return new Response(JSON.stringify({}), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });
    await uploadFile(client, 'notes.txt', 'hello');
    const blob = await downloadFile(client, 'notes.txt');
    await expect(blobText(blob)).resolves.toBe('hello');
    await deleteFile(client, 'notes.txt');
    const seen = storageCalls(fetch).map(([input, init]) => {
      const url = new URL(String(input instanceof Request ? input.url : input));
      return `${(init as RequestInit).method ?? 'GET'} ${url.pathname}`;
    });
    expect(seen).toEqual([
      `POST /storage/v1/object/user-files/owner/notes.txt`,
      `GET /storage/v1/object/user-files/owner/notes.txt`,
      `DELETE /storage/v1/object/user-files`,
    ]);
  });
});
