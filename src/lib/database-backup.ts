import { z } from 'zod'

// Deliberately omit all free-text fields, including host, name and notes.
export const BACKUP_COLUMNS = 'id,user_id,port,transport,created_at,updated_at'
const rowSchema = z.object({
  id: z.uuid(), user_id: z.uuid(), port: z.number().int().min(1).max(65535),
  transport: z.enum(['websocket', 'tls']),
  created_at: z.iso.datetime({ offset: true }), updated_at: z.iso.datetime({ offset: true }),
})

export function safeServerCSV(rows: unknown[]): string {
  const validated = rows.map(row => rowSchema.parse(row))
  return BACKUP_COLUMNS + '\n' + validated.map(row =>
    [row.id, row.user_id, row.port, row.transport, row.created_at, row.updated_at].join(','),
  ).join('\n') + (validated.length ? '\n' : '')
}

export function backupPath(now: Date): string {
  return `database/backups/automatic/relay-servers-${now.toISOString().slice(0, 10)}.csv`
}