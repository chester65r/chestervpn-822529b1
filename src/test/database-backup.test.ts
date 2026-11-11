import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { backupPath, safeServerCSV } from '../lib/database-backup'
import { runDatabaseBackup } from '../lib/database-backup.server'
import { TRANSPORT_IDS } from '../lib/servers'

const adminState = vi.hoisted(() => ({
  result: { data: [] as unknown[], error: null as unknown, count: 0 },
}))

vi.mock('@/integrations/supabase/client.server', () => ({
  supabaseAdmin: {
    from: () => ({
      select: () => ({
        order: () => ({
          limit: () => Promise.resolve(adminState.result),
        }),
      }),
    }),
  },
}))

 describe('safe selected record backup', () => {
  it('excludes credentials, tokens and all free-text fields', () => {
    const csv = safeServerCSV([{
      id: '11111111-1111-4111-8111-111111111111', user_id: '22222222-2222-4222-8222-222222222222',
      port: 443, transport: 'tls', created_at: '2026-10-31T04:40:15Z', updated_at: '2026-10-31T04:40:15Z',
      host: 'secret.example.com', name: 'secret name', notes: 'secret notes',
      password: 'secret password', access_token: 'secret token',
    }])
    expect(csv).toBe('id,user_id,port,transport,created_at,updated_at\n11111111-1111-4111-8111-111111111111,22222222-2222-4222-8222-222222222222,443,tls,2026-10-31T04:40:15Z,2026-10-31T04:40:15Z\n')
  })
  it('refuses token-like transport and malformed safe fields', () => {
    expect(() => safeServerCSV([{ transport: 'secret-token' }])).toThrow()
  })
  it('exports a WireGuard relay server with its UDP port', () => {
    const csv = safeServerCSV([{
      id: '11111111-1111-4111-8111-111111111111', user_id: '22222222-2222-4222-8222-222222222222',
      port: 51820, transport: 'wireguard', created_at: '2026-10-31T04:40:15Z', updated_at: '2026-10-31T04:40:15Z',
    }])
    expect(csv).toBe('id,user_id,port,transport,created_at,updated_at\n11111111-1111-4111-8111-111111111111,22222222-2222-4222-8222-222222222222,51820,wireguard,2026-10-31T04:40:15Z,2026-10-31T04:40:15Z\n')
  })
  it('accepts every transport the relay form offers', () => {
    for (const transport of TRANSPORT_IDS) {
      expect(() => safeServerCSV([{
        id: '11111111-1111-4111-8111-111111111111', user_id: '22222222-2222-4222-8222-222222222222',
        port: 443, transport, created_at: '2026-10-31T04:40:15Z', updated_at: '2026-10-31T04:40:15Z',
      }])).not.toThrow()
    }
  })
  it('exports a header for zero records', () => {
    expect(safeServerCSV([])).toBe('id,user_id,port,transport,created_at,updated_at\n')
  })
  it('uses a UTC daily snapshot path', () => {
    expect(backupPath(new Date('2026-10-31T23:00:00-03:00'))).toBe('database/backups/automatic/relay-servers-2026-11-01.csv')
  })
})

const backupRow = {
  id: '11111111-1111-4111-8111-111111111111', user_id: '22222222-2222-4222-8222-222222222222',
  port: 51820, transport: 'wireguard', created_at: '2026-10-31T04:40:15Z', updated_at: '2026-10-31T04:40:15Z',
}
const REPO_URL = 'https://api.github.com/repos/chester65r/chestervpn-822529b1'

type FetchCall = { url: string, init: RequestInit | undefined }

function stubGitHub(options: {
  repo?: Record<string, unknown>
  initial?: { content: string, sha: string } | null
} = {}) {
  const calls: FetchCall[] = []
  let stored = options.initial ?? null
  const fetchMock = vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input)
    calls.push({ url, init })
    if (url === REPO_URL) {
      return Response.json(options.repo ?? { private: true, default_branch: 'main' })
    }
    if (url.includes('/contents/')) {
      if (init?.method === 'PUT') {
        const body = JSON.parse(String(init.body)) as { content: string }
        stored = { content: body.content, sha: 'saved-sha' }
        return Response.json({})
      }
      if (!stored) return new Response('Not Found', { status: 404 })
      return Response.json({ content: stored.content, sha: stored.sha })
    }
    throw new Error(`Unexpected fetch: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { calls, fetchMock }
}

function headersOf(call: FetchCall): Record<string, string> {
  return (call.init?.headers ?? {}) as Record<string, string>
}

describe('database backup over the direct GitHub API', () => {
  beforeEach(() => {
    vi.stubEnv('BACKUP_GITHUB_TOKEN', 'test-backup-token')
    vi.spyOn(console, 'error').mockImplementation(() => {})
    adminState.result = { data: [backupRow], error: null, count: 1 }
  })
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('fails closed when BACKUP_GITHUB_TOKEN is not configured', async () => {
    delete process.env['BACKUP_GITHUB_TOKEN']
    const { fetchMock } = stubGitHub()
    await expect(runDatabaseBackup()).rejects.toThrow('Backup connection is not configured')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('creates the snapshot with direct api.github.com calls authorized by BACKUP_GITHUB_TOKEN', async () => {
    const { calls } = stubGitHub()
    await expect(runDatabaseBackup()).resolves.toEqual({ rows: 1, changed: true })
    expect(calls.length).toBeGreaterThan(0)
    for (const call of calls) {
      expect(call.url.startsWith('https://api.github.com/')).toBe(true)
      expect(call.url).not.toContain('lovable')
      expect(headersOf(call)['Authorization']).toBe('Bearer test-backup-token')
      expect(headersOf(call)).not.toHaveProperty('X-Connection-Api-Key')
    }
    expect(calls[0]?.url).toBe(REPO_URL)
    const put = calls.find(call => call.init?.method === 'PUT')
    expect(put?.url).toBe(`${REPO_URL}/contents/${backupPath(new Date())}`)
    const body = JSON.parse(String(put?.init?.body)) as { content: string, sha?: string }
    expect(body.content).toBe(btoa(safeServerCSV([backupRow])))
    expect(body).not.toHaveProperty('sha')
  })

  it('short-circuits without a write when the stored snapshot is unchanged', async () => {
    const { calls } = stubGitHub({
      initial: { content: btoa(safeServerCSV([backupRow])), sha: 'existing-sha' },
    })
    await expect(runDatabaseBackup()).resolves.toEqual({ rows: 1, changed: false })
    expect(calls.some(call => call.init?.method === 'PUT')).toBe(false)
  })

  it('passes the existing file sha when updating a changed snapshot', async () => {
    const { calls } = stubGitHub({
      initial: { content: btoa('stale,content\n'), sha: 'existing-sha' },
    })
    await expect(runDatabaseBackup()).resolves.toEqual({ rows: 1, changed: true })
    const put = calls.find(call => call.init?.method === 'PUT')
    const body = JSON.parse(String(put?.init?.body)) as { sha?: string }
    expect(body.sha).toBe('existing-sha')
  })

  it('refuses to back up to a public repository', async () => {
    const { calls } = stubGitHub({ repo: { private: false, default_branch: 'main' } })
    await expect(runDatabaseBackup()).rejects.toThrow('Backup refused: repository must be private')
    expect(calls.some(call => call.url.includes('/contents/'))).toBe(false)
  })

  it('refuses an incomplete record snapshot', async () => {
    adminState.result = { data: [backupRow], error: null, count: 2 }
    stubGitHub()
    await expect(runDatabaseBackup()).rejects.toThrow('Backup refused: incomplete record snapshot')
  })
})