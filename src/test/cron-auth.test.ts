import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authenticateCronRequest } from '../integrations/supabase/cron-auth'

const ENV_KEYS = ['CRON_SECRET', 'CRON_SECRET_PREVIOUS', 'LOVABLE_CRON_SECRET', 'LOVABLE_CRON_SECRET_PREVIOUS'] as const
const savedEnv: Record<string, string | undefined> = {}

function cronRequest(token?: string): Request {
  return new Request('https://example.test/api/public/database-backup', {
    headers: token === undefined ? {} : { authorization: `Bearer ${token}` },
  })
}

describe('cron request authentication', () => {
  beforeEach(() => {
    for (const key of ENV_KEYS) {
      savedEnv[key] = process.env[key]
      delete process.env[key]
    }
  })
  afterEach(() => {
    for (const key of ENV_KEYS) {
      if (savedEnv[key] === undefined) delete process.env[key]
      else process.env[key] = savedEnv[key]
    }
    vi.unstubAllEnvs()
  })

  it('fails closed with a server error when CRON_SECRET is unset', async () => {
    const denied = await authenticateCronRequest(cronRequest('anything'))
    expect(denied?.status).toBe(500)
  })

  it('ignores the retired LOVABLE_CRON_SECRET variables', async () => {
    vi.stubEnv('LOVABLE_CRON_SECRET', 'legacy-secret')
    const denied = await authenticateCronRequest(cronRequest('legacy-secret'))
    expect(denied?.status).toBe(500)
  })

  it('rejects requests without a bearer token', async () => {
    vi.stubEnv('CRON_SECRET', 'current-secret')
    const denied = await authenticateCronRequest(cronRequest())
    expect(denied?.status).toBe(401)
  })

  it('rejects a wrong bearer token', async () => {
    vi.stubEnv('CRON_SECRET', 'current-secret')
    const denied = await authenticateCronRequest(cronRequest('wrong-secret'))
    expect(denied?.status).toBe(401)
  })

  it('accepts the current CRON_SECRET', async () => {
    vi.stubEnv('CRON_SECRET', 'current-secret')
    await expect(authenticateCronRequest(cronRequest('current-secret'))).resolves.toBeNull()
  })

  it('still accepts CRON_SECRET_PREVIOUS during rotation', async () => {
    vi.stubEnv('CRON_SECRET', 'current-secret')
    vi.stubEnv('CRON_SECRET_PREVIOUS', 'previous-secret')
    await expect(authenticateCronRequest(cronRequest('previous-secret'))).resolves.toBeNull()
  })
})
