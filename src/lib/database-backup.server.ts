import { BACKUP_COLUMNS, backupPath, safeServerCSV } from './database-backup'

const REPO = '/repos/chester65r/chestervpn-822529b1'

export async function runDatabaseBackup() {
  const apiKey = process.env['LOVABLE_API_KEY']
  const connectionKey = process.env['GITHUB_API_KEY']
  if (!apiKey || !connectionKey) throw new Error('Backup connection is not configured')
  const headers = {
    Authorization: `Bearer ${apiKey}`, 'X-Connection-Api-Key': connectionKey,
    Accept: 'application/vnd.github+json', 'Content-Type': 'application/json',
  }
  async function github(path: string, init?: RequestInit, allowMissing = false) {
    const response = await fetch(`https://connector-gateway.lovable.dev/github${path}`, { ...init, headers })
    if (allowMissing && response.status === 404) return null
    if (!response.ok) {
      const body = await response.text()
      console.error(`GitHub backup request failed [${response.status}]: ${body}`)
      throw new Error(`GitHub request failed [${response.status}]: ${body}`)
    }
    return response.json()
  }
  const repo = await github(REPO)
  if (repo?.private !== true) throw new Error('Backup refused: repository must be private')
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server')
  const rows: unknown[] = []
  // One statement avoids inconsistent pagination during concurrent record changes.
  const { data, error, count } = await supabaseAdmin.from('relay_servers')
    .select(BACKUP_COLUMNS, { count: 'exact' }).order('id').limit(10000)
  if (error) throw new Error('Database backup read failed')
  rows.push(...(data ?? []))
  if (count !== rows.length) throw new Error('Backup refused: incomplete record snapshot')
  const csv = safeServerCSV(rows)
  const path = backupPath(new Date())
  const current = await github(`${REPO}/contents/${path}?ref=${encodeURIComponent(repo.default_branch)}`, undefined, true)
  const content = btoa(csv)
  // Idempotent retries and no-change runs do not create redundant commits.
  if (current?.content?.replace(/\s/g, '') === content) return { rows: rows.length, changed: false }
  await github(`${REPO}/contents/${path}`, {
    method: 'PUT', body: JSON.stringify({
      message: 'Save safe daily server-record snapshot', branch: repo.default_branch,
      content, ...(current?.sha ? { sha: current.sha } : {}),
    }),
  })
  const saved = await github(`${REPO}/contents/${path}?ref=${encodeURIComponent(repo.default_branch)}`)
  if (saved?.content?.replace(/\s/g, '') !== content) throw new Error('Backup read-back mismatch')
  return { rows: rows.length, changed: true }
}