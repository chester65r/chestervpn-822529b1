import { describe, expect, it } from 'vitest'
import { backupPath, safeServerCSV } from '../lib/database-backup'

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
  it('exports a header for zero records', () => {
    expect(safeServerCSV([])).toBe('id,user_id,port,transport,created_at,updated_at\n')
  })
  it('uses a UTC daily snapshot path', () => {
    expect(backupPath(new Date('2026-10-31T23:00:00-03:00'))).toBe('database/backups/automatic/relay-servers-2026-11-01.csv')
  })
})