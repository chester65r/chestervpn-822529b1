import { afterEach, describe, expect, it, vi } from 'vitest';
import { additionalBackend, createAdditionalBackendReader } from '../lib/additional-backend';

afterEach(() => vi.unstubAllGlobals());

describe('isolated additional backend', () => {
  it('reads only the additional project without forwarding a bearer token', async () => {
    const request = vi.fn().mockResolvedValue(new Response('[]', {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', request);
    const client = createAdditionalBackendReader();
    await client.from('public_status').select('status');
    expect(request).toHaveBeenCalledOnce();
    const [url, init] = request.mock.calls[0];
    expect(String(url)).toBe(`${additionalBackend.url}/rest/v1/public_status?select=status`);
    expect(new Headers(init.headers).has('Authorization')).toBe(false);
    expect(new Headers(init.headers).get('apikey')).toBe(additionalBackend.publishableKey);
  });

  it('blocks writes before any request reaches the external project', async () => {
    const request = vi.fn();
    vi.stubGlobal('fetch', request);
    const { error } = await createAdditionalBackendReader().from('public_status').insert({ status: 'up' });
    expect(error?.message).toContain('read-only');
    expect(request).not.toHaveBeenCalled();
  });
});