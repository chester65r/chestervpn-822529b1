import { createFileRoute } from '@tanstack/react-router'
import { createClient } from '@supabase/supabase-js'
import type { Database } from '@/integrations/supabase/types'
import { hashRelayProbeToken, tokensMatch } from '@/lib/relay-probe'

const MAX_CLOCK_SKEW_MS = 2 * 60 * 1000

export const Route = createFileRoute('/api/relay-probe')({
  staticData: { sitemap: false },
  server: {
    handlers: {
      POST: async ({ request }) => {
        try {
          const body = await request.json() as {
            server_id?: string
            token?: string
            sent_at?: string
            status?: 'online' | 'degraded'
            latency_ms?: number
          }
          if (!body.server_id || !body.token || !body.sent_at) {
            return Response.json({ ok: false, error: 'Invalid probe payload' }, { status: 400 })
          }
          const sentAt = Date.parse(body.sent_at)
          if (!Number.isFinite(sentAt) || Math.abs(Date.now() - sentAt) > MAX_CLOCK_SKEW_MS) {
            return Response.json({ ok: false, error: 'Stale probe payload' }, { status: 401 })
          }

          const url = process.env.SUPABASE_URL
          const key = process.env.SUPABASE_SERVICE_ROLE_KEY
          if (!url || !key) return Response.json({ ok: false, error: 'Probe service unavailable' }, { status: 503 })
          const supabase = createClient<Database>(url, key, { auth: { persistSession: false, autoRefreshToken: false } })
          const { data: server, error } = await supabase
            .from('relay_servers')
            .select('id, probe_token_hash')
            .eq('id', body.server_id)
            .maybeSingle()
          if (error || !server?.probe_token_hash) return Response.json({ ok: false, error: 'Probe rejected' }, { status: 401 })

          const suppliedHash = await hashRelayProbeToken(body.token)
          if (!tokensMatch(suppliedHash, server.probe_token_hash)) {
            return Response.json({ ok: false, error: 'Probe rejected' }, { status: 401 })
          }

          const status = body.status ?? 'online'
          const latency = typeof body.latency_ms === 'number' && Number.isFinite(body.latency_ms)
            ? Math.max(0, Math.min(Math.round(body.latency_ms), 60_000))
            : null
          await supabase.from('relay_servers').update({
            status,
            last_checked_at: new Date().toISOString(),
            last_latency_ms: latency,
          }).eq('id', body.server_id)
          await supabase.from('audit_logs').insert({
            actor_id: null,
            action: 'relay.agent_heartbeat',
            entity_type: 'relay_server',
            entity_id: body.server_id,
            metadata: { status, latencyMs: latency },
          })
          return Response.json({ ok: true, received_at: new Date().toISOString() }, { headers: { 'Cache-Control': 'no-store' } })
        } catch {
          return Response.json({ ok: false, error: 'Invalid probe request' }, { status: 400 })
        }
      },
    },
  },
})
