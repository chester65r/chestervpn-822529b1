import { createFileRoute } from '@tanstack/react-router'
import { authenticateCronRequest } from '@/integrations/supabase/cron-auth'

export const Route = createFileRoute('/api/public/database-backup')({
  staticData: { sitemap: false },
  server: { handlers: { POST: async ({ request }) => {
    const denied = await authenticateCronRequest(request)
    if (denied) return denied
    try {
      const { runDatabaseBackup } = await import('@/lib/database-backup.server')
      const result = await runDatabaseBackup()
      return Response.json({ ok: true, ...result }, { headers: { 'Cache-Control': 'no-store' } })
    } catch (error) {
      // Never return provider responses, private rows or credentials to HTTP callers.
      console.error('[database-backup]', error instanceof Error ? error.message : error)
      return Response.json({ ok: false, error: 'Backup failed; check server logs' }, { status: 500 })
    }
  } } },
})