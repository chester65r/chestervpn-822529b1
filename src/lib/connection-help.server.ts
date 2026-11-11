import {
  buildConnectionHelpMessages,
  connectionHelpErrorForStatus,
  extractConnectionHelpText,
  resolveConnectionHelpConfig,
  type ConnectionDiagnostics,
} from './connection-help'

const REQUEST_TIMEOUT_MS = 30_000

export type ConnectionHelpResult = { text: string } | { error: string }

// Server-side only: calls the operator-configured OpenAI-compatible
// chat-completions endpoint (AI_HELP_API_KEY / AI_HELP_API_URL /
// AI_HELP_MODEL) directly. Fails closed with an honest "not configured"
// error when no key is set, and never returns provider internals or the
// API key to the client.
export async function requestConnectionExplanation(
  data: ConnectionDiagnostics,
): Promise<ConnectionHelpResult> {
  const config = resolveConnectionHelpConfig(process.env)
  if (!config) return { error: "The AI helper isn't configured yet." }
  let res: Response
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  try {
    res = await fetch(config.apiUrl, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${config.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: config.model,
        messages: buildConnectionHelpMessages(data),
      }),
      signal: controller.signal,
    })
  } catch (e) {
    console.error('[connection-help] network', e)
    return { error: "Couldn't reach the AI helper. Try again shortly." }
  } finally {
    clearTimeout(timer)
  }
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    console.error('[connection-help]', res.status, body.slice(0, 300))
    return { error: connectionHelpErrorForStatus(res.status) }
  }
  const payload: unknown = await res.json().catch(() => null)
  const text = extractConnectionHelpText(payload)
  return text ? { text } : { error: 'The AI helper returned no explanation.' }
}
