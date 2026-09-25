import { createSseParser } from './sse'
import type {
  ApiError,
  ConversationDetail,
  ConversationSummary,
  DoneEvent,
  HealthResponse,
  MetaEvent,
  Rating,
  SourcesEvent,
  TokenEvent,
} from './types'

export class ApiRequestError extends Error {
  readonly status: number
  readonly code: string
  readonly retryAfterSeconds: number | null

  constructor(status: number, body: Partial<ApiError> | null) {
    super(body?.message ?? `Request failed with HTTP ${status}.`)
    this.status = status
    this.code = body?.code ?? (status === 0 ? 'NETWORK' : 'HTTP_' + status)
    this.retryAfterSeconds = body?.retryAfterSeconds ?? null
  }
}

async function readError(response: Response): Promise<ApiRequestError> {
  const body = await response
    .json()
    .then((json: unknown) => json as Partial<ApiError>)
    .catch(() => null)
  if (response.status === 429 && body && body.retryAfterSeconds == null) {
    const header = Number(response.headers.get('Retry-After'))
    if (Number.isFinite(header) && header > 0) body.retryAfterSeconds = header
  }
  return new ApiRequestError(response.status, body)
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
  } catch {
    throw new ApiRequestError(0, { message: "Can't reach the server. Check your connection." })
  }
  if (!response.ok) throw await readError(response)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export const api = {
  listConversations: () => request<ConversationSummary[]>('/api/conversations'),
  getConversation: (id: string) => request<ConversationDetail>(`/api/conversations/${id}`),
  renameConversation: (id: string, title: string) =>
    request<ConversationSummary>(`/api/conversations/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ title }),
    }),
  deleteConversation: (id: string) => request<undefined>(`/api/conversations/${id}`, { method: 'DELETE' }),
  sendFeedback: (conversationId: string, messageIndex: number, rating: Rating) =>
    request<undefined>('/api/feedback', {
      method: 'POST',
      body: JSON.stringify({ conversationId, messageIndex, rating }),
    }),
  health: async (): Promise<HealthResponse> => {
    // Actuator answers 503 with the same JSON body when a component is down.
    const response = await fetch('/api/health')
    return (await response.json()) as HealthResponse
  },
}

export interface StreamHandlers {
  onMeta: (meta: MetaEvent) => void
  onToken: (text: string) => void
  onSources: (event: SourcesEvent) => void
  onDone: (done: DoneEvent) => void
  onError: (error: ApiError) => void
}

export interface ChatRequest {
  conversationId: string | null
  message: string
  regenerate?: boolean
}

/**
 * POSTs a chat message and consumes the SSE response with fetch + ReadableStream
 * (EventSource can't send a POST body). Resolves when the stream ends; rejects with
 * ApiRequestError for non-2xx responses and with an AbortError when `signal` is aborted.
 */
export async function streamChat(
  body: ChatRequest,
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  let response: Response
  try {
    response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body),
      signal,
    })
  } catch (e) {
    if (signal.aborted) throw e
    throw new ApiRequestError(0, { message: "Can't reach the server. Check your connection." })
  }
  if (!response.ok || !response.body) throw await readError(response)

  const parser = createSseParser(({ event, data }) => {
    const payload: unknown = JSON.parse(data)
    switch (event) {
      case 'meta':
        handlers.onMeta(payload as MetaEvent)
        break
      case 'token':
        handlers.onToken((payload as TokenEvent).text)
        break
      case 'sources':
        handlers.onSources(payload as SourcesEvent)
        break
      case 'done':
        handlers.onDone(payload as DoneEvent)
        break
      case 'error':
        handlers.onError(payload as ApiError)
        break
    }
  })

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      parser.feed(value)
    }
    parser.end()
  } finally {
    reader.releaseLock()
  }
}
