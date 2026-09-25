export type Role = 'user' | 'assistant'
export type Rating = 'UP' | 'DOWN'

export interface SourceRef {
  title: string
  url: string
}

export interface ConversationSummary {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface MessageView {
  index: number
  role: Role
  content: string
  sources: SourceRef[]
  feedback: Rating | null
}

export interface ConversationDetail extends ConversationSummary {
  messages: MessageView[]
}

export interface ApiError {
  code: string
  message: string
  retryAfterSeconds: number | null
}

/** SSE payloads from POST /api/chat/stream */
export interface MetaEvent {
  conversationId: string
  title: string
  created: boolean
  userMessageIndex: number
}
export interface TokenEvent {
  text: string
}
export interface SourcesEvent {
  sources: SourceRef[]
}
export interface DoneEvent {
  conversationId: string
  messageIndex: number
}

export interface HealthResponse {
  status: string
  components?: {
    index?: { status: string; details?: { state?: string; documents?: number; chunks?: number } }
    model?: { status: string; details?: { name?: string } }
  }
}
