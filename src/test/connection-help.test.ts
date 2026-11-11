import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  CONNECTION_HELP_SYSTEM_PROMPT,
  DEFAULT_AI_HELP_API_URL,
  DEFAULT_AI_HELP_MODEL,
  extractConnectionHelpText,
  resolveConnectionHelpConfig,
  type ConnectionDiagnostics,
} from '../lib/connection-help'
import { requestConnectionExplanation } from '../lib/connection-help.server'

const ENV_KEYS = ['AI_HELP_API_KEY', 'AI_HELP_API_URL', 'AI_HELP_MODEL'] as const
const savedEnv: Record<string, string | undefined> = {}

const diagnostics: ConnectionDiagnostics = {
  status: 'disconnected',
  serverConfigured: true,
  port: '51820',
  sniSet: false,
  payloadSet: false,
  profileErrors: {},
  browserOnline: true,
  sessionSeconds: 0,
  downloadMbps: 0,
  uploadMbps: 0,
  lastEvent: 'Handshake timed out',
}

type FetchCall = { url: string, init: RequestInit | undefined }

function stubFetch(handler: (url: string, init?: RequestInit) => Promise<Response> | Response) {
  const calls: FetchCall[] = []
  const fetchMock = vi.fn(async (input: unknown, init?: RequestInit) => {
    calls.push({ url: String(input), init })
    return handler(String(input), init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { calls, fetchMock }
}

describe('AI connection-help configuration', () => {
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

  it('fails closed (null config) when AI_HELP_API_KEY is unset or blank', () => {
    expect(resolveConnectionHelpConfig({})).toBeNull()
    expect(resolveConnectionHelpConfig({ AI_HELP_API_KEY: '   ' })).toBeNull()
  })

  it('applies the Gemini OpenAI-compatible defaults when only the key is set', () => {
    expect(resolveConnectionHelpConfig({ AI_HELP_API_KEY: ' key-123 ' })).toEqual({
      apiKey: 'key-123',
      apiUrl: DEFAULT_AI_HELP_API_URL,
      model: DEFAULT_AI_HELP_MODEL,
    })
    expect(DEFAULT_AI_HELP_API_URL).toBe(
      'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions',
    )
    expect(DEFAULT_AI_HELP_MODEL).toBe('gemini-2.0-flash')
  })

  it('honours operator overrides and falls back on blank overrides', () => {
    expect(
      resolveConnectionHelpConfig({
        AI_HELP_API_KEY: 'k',
        AI_HELP_API_URL: 'https://example.test/v1/chat/completions',
        AI_HELP_MODEL: 'some-model',
      }),
    ).toEqual({
      apiKey: 'k',
      apiUrl: 'https://example.test/v1/chat/completions',
      model: 'some-model',
    })
    expect(
      resolveConnectionHelpConfig({ AI_HELP_API_KEY: 'k', AI_HELP_API_URL: ' ', AI_HELP_MODEL: '' }),
    ).toEqual({ apiKey: 'k', apiUrl: DEFAULT_AI_HELP_API_URL, model: DEFAULT_AI_HELP_MODEL })
  })
})

describe('AI connection help over the direct provider API', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('fails closed without calling the provider when AI_HELP_API_KEY is not configured', async () => {
    delete process.env['AI_HELP_API_KEY']
    const { fetchMock } = stubFetch(() => {
      throw new Error('fetch must not be called')
    })
    await expect(requestConnectionExplanation(diagnostics)).resolves.toEqual({
      error: "The AI helper isn't configured yet.",
    })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('maps a successful provider response to { text } and sends the troubleshooting prompt', async () => {
    vi.stubEnv('AI_HELP_API_KEY', 'test-ai-key')
    const { calls } = stubFetch(() =>
      Response.json({
        choices: [{ message: { content: '  Your server is not answering. Check the port.  ' } }],
      }),
    )
    await expect(requestConnectionExplanation(diagnostics)).resolves.toEqual({
      text: 'Your server is not answering. Check the port.',
    })
    expect(calls).toHaveLength(1)
    const [call] = calls
    expect(call?.url).toBe(DEFAULT_AI_HELP_API_URL)
    expect(call?.url).not.toContain('lovable')
    expect(call?.init?.method).toBe('POST')
    const headers = (call?.init?.headers ?? {}) as Record<string, string>
    expect(headers['Authorization']).toBe('Bearer test-ai-key')
    expect(call?.init?.signal).toBeTruthy()
    const body = JSON.parse(String(call?.init?.body)) as {
      model: string
      stream?: boolean
      messages: { role: string, content: string }[]
    }
    expect(body.model).toBe(DEFAULT_AI_HELP_MODEL)
    expect(body.stream).toBeUndefined()
    expect(body.messages[0]).toEqual({ role: 'system', content: CONNECTION_HELP_SYSTEM_PROMPT })
    expect(body.messages[0]?.content).toContain('Never suggest bypassing carrier billing')
    expect(body.messages[1]?.role).toBe('user')
    expect(body.messages[1]?.content).toContain('Handshake timed out')
  })

  it('uses the configured AI_HELP_API_URL and AI_HELP_MODEL', async () => {
    vi.stubEnv('AI_HELP_API_KEY', 'test-ai-key')
    vi.stubEnv('AI_HELP_API_URL', 'https://example.test/v1/chat/completions')
    vi.stubEnv('AI_HELP_MODEL', 'operator-model')
    const { calls } = stubFetch(() =>
      Response.json({ choices: [{ message: { content: 'All good.' } }] }),
    )
    await expect(requestConnectionExplanation(diagnostics)).resolves.toEqual({ text: 'All good.' })
    expect(calls[0]?.url).toBe('https://example.test/v1/chat/completions')
    const body = JSON.parse(String(calls[0]?.init?.body)) as { model: string }
    expect(body.model).toBe('operator-model')
  })

  it('maps a 429 to the busy error without leaking the provider body', async () => {
    vi.stubEnv('AI_HELP_API_KEY', 'test-ai-key')
    stubFetch(() => new Response('provider-internal-quota-detail', { status: 429 }))
    const result = await requestConnectionExplanation(diagnostics)
    expect(result).toEqual({
      error: 'The AI helper is busy. Please wait a moment and try again.',
    })
    expect(JSON.stringify(result)).not.toContain('provider-internal-quota-detail')
    expect(JSON.stringify(result)).not.toContain('test-ai-key')
  })

  it('maps other provider failures to a clean generic error', async () => {
    vi.stubEnv('AI_HELP_API_KEY', 'test-ai-key')
    stubFetch(() => new Response('stack trace internals', { status: 500 }))
    await expect(requestConnectionExplanation(diagnostics)).resolves.toEqual({
      error: "The AI helper couldn't answer right now.",
    })
  })

  it('reports an unreachable provider instead of throwing', async () => {
    vi.stubEnv('AI_HELP_API_KEY', 'test-ai-key')
    stubFetch(() => {
      throw new Error('socket hang up')
    })
    await expect(requestConnectionExplanation(diagnostics)).resolves.toEqual({
      error: "Couldn't reach the AI helper. Try again shortly.",
    })
  })

  it('treats an empty provider answer as no explanation', async () => {
    vi.stubEnv('AI_HELP_API_KEY', 'test-ai-key')
    stubFetch(() => Response.json({ choices: [{ message: { content: '   ' } }] }))
    await expect(requestConnectionExplanation(diagnostics)).resolves.toEqual({
      error: 'The AI helper returned no explanation.',
    })
  })
})

describe('extractConnectionHelpText', () => {
  it('returns empty for malformed payloads', () => {
    expect(extractConnectionHelpText(null)).toBe('')
    expect(extractConnectionHelpText('nope')).toBe('')
    expect(extractConnectionHelpText({})).toBe('')
    expect(extractConnectionHelpText({ choices: [] })).toBe('')
    expect(extractConnectionHelpText({ choices: [{ message: { content: 42 } }] })).toBe('')
  })

  it('trims string content', () => {
    expect(extractConnectionHelpText({ choices: [{ message: { content: '  hi ' } }] })).toBe('hi')
  })
})
