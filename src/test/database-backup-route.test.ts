import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const backupMock = vi.hoisted(() => ({ run: vi.fn() }))

vi.mock('@/lib/database-backup.server', () => ({ runDatabaseBackup: backupMock.run }))

import { Route } from '../routes/api/public/database-backup'

type Handler = (context: { request: Request }) => Promise<Response>
const handlers = (
  Route as unknown as { options: { server: { handlers: Record<string, Handler> } } }
).options.server.handlers

function cronRequest(token?: string): Request {
  return new Request('https://example.test/api/public/database-backup', {
    headers: token === undefined ? {} : { authorization: `Bearer ${token}` },
  })
}

describe('database backup route handlers', () => {
  beforeEach(() => {
    vi.stubEnv('CRON_SECRET', 'route-secret')
    backupMock.run.mockReset().mockResolvedValue({ rows: 3, changed: true })
  })
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('exposes both POST and GET handlers', () => {
    expect(typeof handlers['POST']).toBe('function')
    expect(typeof handlers['GET']).toBe('function')
  })

  it('rejects unauthenticated GET requests exactly like POST', async () => {
    const getDenied = await handlers['GET']!({ request: cronRequest() })
    const postDenied = await handlers['POST']!({ request: cronRequest() })
    expect(getDenied.status).toBe(401)
    expect(postDenied.status).toBe(401)
    expect(backupMock.run).not.toHaveBeenCalled()
  })

  it('runs the backup for an authenticated GET (Vercel Cron invocation shape)', async () => {
    const response = await handlers['GET']!({ request: cronRequest('route-secret') })
    expect(response.status).toBe(200)
    await expect(response.json()).resolves.toEqual({ ok: true, rows: 3, changed: true })
    expect(backupMock.run).toHaveBeenCalledTimes(1)
  })

  it('runs the backup for an authenticated POST with the same result', async () => {
    const response = await handlers['POST']!({ request: cronRequest('route-secret') })
    expect(response.status).toBe(200)
    await expect(response.json()).resolves.toEqual({ ok: true, rows: 3, changed: true })
    expect(backupMock.run).toHaveBeenCalledTimes(1)
  })
})
