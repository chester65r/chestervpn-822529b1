// Pure helpers for the AI connection-help feature. The provider is any
// OpenAI-compatible chat-completions endpoint configured by the project
// operator through server env (AI_HELP_*); no third-party gateway sits
// between this deployment and the provider.

export interface ConnectionDiagnostics {
  status: 'disconnected' | 'connecting' | 'connected'
  serverConfigured: boolean
  port: string
  sniSet: boolean
  payloadSet: boolean
  profileErrors: Record<string, string>
  browserOnline: boolean
  sessionSeconds: number
  downloadMbps: number
  uploadMbps: number
  lastEvent: string
}

export const CONNECTION_HELP_SYSTEM_PROMPT = `You are the troubleshooting helper inside "Tunnel Console", a VPN dashboard.
The browser dashboard simulates the connection; real tunnels run in the companion Android WireGuard app against the user's own server.
Using ONLY the diagnostics given, explain in plain, friendly language what is wrong (or confirm everything looks fine) and list 2-4 concrete next steps.
Keep it under 120 words. No markdown headings. Never suggest bypassing carrier billing, spoofing hosts, or using carrier/social hostnames.`

export const DEFAULT_AI_HELP_API_URL =
  'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions'
export const DEFAULT_AI_HELP_MODEL = 'gemini-2.0-flash'

export interface ConnectionHelpConfig {
  apiKey: string
  apiUrl: string
  model: string
}

// Returns null when AI_HELP_API_KEY is unset or blank: the feature fails
// closed (the UI honestly reports the helper as not configured) instead of
// inventing an answer. Blank URL/model values fall back to the defaults.
export function resolveConnectionHelpConfig(
  env: Record<string, string | undefined>,
): ConnectionHelpConfig | null {
  const apiKey = env['AI_HELP_API_KEY']?.trim()
  if (!apiKey) return null
  return {
    apiKey,
    apiUrl: env['AI_HELP_API_URL']?.trim() || DEFAULT_AI_HELP_API_URL,
    model: env['AI_HELP_MODEL']?.trim() || DEFAULT_AI_HELP_MODEL,
  }
}

export function buildConnectionHelpMessages(data: ConnectionDiagnostics) {
  return [
    { role: 'system', content: CONNECTION_HELP_SYSTEM_PROMPT },
    { role: 'user', content: `Diagnostics:\n${JSON.stringify(data, null, 2)}` },
  ]
}

// Maps provider HTTP statuses to clean, user-facing errors. Provider
// response bodies are never forwarded to the client: they can carry
// provider internals, and the API key must never leak either.
export function connectionHelpErrorForStatus(status: number): string {
  if (status === 429) return 'The AI helper is busy. Please wait a moment and try again.'
  if (status === 401 || status === 403) return "The AI helper isn't available right now."
  return "The AI helper couldn't answer right now."
}

// Extracts the assistant text from an OpenAI-compatible chat-completions
// response body; returns '' for any malformed or unexpected shape.
export function extractConnectionHelpText(payload: unknown): string {
  if (typeof payload !== 'object' || payload === null) return ''
  const choices = (payload as { choices?: unknown }).choices
  if (!Array.isArray(choices)) return ''
  const message = (choices[0] as { message?: unknown } | undefined)?.message
  const content = (message as { content?: unknown } | undefined)?.content
  return typeof content === 'string' ? content.trim() : ''
}
