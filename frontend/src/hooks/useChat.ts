import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'

import { ApiRequestError, api, streamChat } from '@/lib/api'
import type { ConversationDetail, ConversationSummary, Rating, Role, SourceRef } from '@/lib/types'

export type MessageStatus = 'streaming' | 'done' | 'stopped' | 'error'

export interface UiMessage {
  key: string
  /** Server-side position in the conversation; null until the server has confirmed it. */
  index: number | null
  role: Role
  content: string
  sources: SourceRef[]
  feedback: Rating | null
  status: MessageStatus
  error?: string
  /** For a failed answer: whether the server stored the question (decides how to retry). */
  questionStored?: boolean
}

let keySeq = 0
const nextKey = () => `m${Date.now().toString(36)}${(keySeq++).toString(36)}`

function fromDetail(detail: ConversationDetail): UiMessage[] {
  return detail.messages.map((m) => ({
    key: nextKey(),
    index: m.index,
    role: m.role,
    content: m.content,
    sources: m.sources,
    feedback: m.feedback,
    status: 'done',
  }))
}

function describe(error: unknown): { message: string; retryAfter: number | null } {
  if (error instanceof ApiRequestError) {
    return { message: error.message, retryAfter: error.retryAfterSeconds }
  }
  return { message: 'Something went wrong. Please try again.', retryAfter: null }
}

export function useChat() {
  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [messages, setMessages] = useState<UiMessage[]>([])
  const [streaming, setStreaming] = useState(false)
  const [loadingConversation, setLoadingConversation] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const activeIdRef = useRef<string | null>(null)
  useEffect(() => {
    activeIdRef.current = activeId
  }, [activeId])

  const refreshList = useCallback(async () => {
    try {
      setConversations(await api.listConversations())
    } catch (e) {
      toast.error(describe(e).message)
    }
  }, [])

  useEffect(() => {
    api
      .listConversations()
      .then(setConversations)
      .catch((e: unknown) => toast.error(describe(e).message))
  }, [])

  const patchMessage = useCallback((key: string, patch: (m: UiMessage) => Partial<UiMessage>) => {
    setMessages((prev) => prev.map((m) => (m.key === key ? { ...m, ...patch(m) } : m)))
  }, [])

  /** Re-read the conversation from the server (after Stop, the partial answer's index is only known there). */
  const syncFromServer = useCallback(async (id: string) => {
    try {
      const detail = await api.getConversation(id)
      if (activeIdRef.current === id) setMessages(fromDetail(detail))
    } catch {
      /* keep local state */
    }
  }, [])

  const runTurn = useCallback(
    async (text: string, opts: { regenerate: boolean; keepUserKey?: string }) => {
      const assistantKey = nextKey()
      const userKey = opts.keepUserKey ?? nextKey()
      setMessages((prev) => {
        const base = opts.keepUserKey
          ? prev
          : [
              ...prev,
              {
                key: userKey,
                index: null,
                role: 'user' as const,
                content: text,
                sources: [],
                feedback: null,
                status: 'done' as const,
              },
            ]
        return [
          ...base,
          {
            key: assistantKey,
            index: null,
            role: 'assistant',
            content: '',
            sources: [],
            feedback: null,
            status: 'streaming',
          },
        ]
      })

      const controller = new AbortController()
      abortRef.current = controller
      setStreaming(true)
      let questionStored = false
      let conversationId = activeIdRef.current

      try {
        await streamChat(
          { conversationId, message: text, regenerate: opts.regenerate },
          {
            onMeta: (meta) => {
              questionStored = true
              conversationId = meta.conversationId
              patchMessage(userKey, () => ({ index: meta.userMessageIndex }))
              if (meta.created) {
                activeIdRef.current = meta.conversationId
                setActiveId(meta.conversationId)
                const now = new Date().toISOString()
                setConversations((prev) => [
                  { id: meta.conversationId, title: meta.title, createdAt: now, updatedAt: now },
                  ...prev,
                ])
              }
            },
            onToken: (delta) => patchMessage(assistantKey, (m) => ({ content: m.content + delta })),
            onSources: ({ sources }) => patchMessage(assistantKey, () => ({ sources })),
            onDone: (done) => {
              patchMessage(assistantKey, () => ({ index: done.messageIndex, status: 'done' }))
              setConversations((prev) => {
                const current = prev.find((c) => c.id === done.conversationId)
                if (!current) return prev
                const bumped = { ...current, updatedAt: new Date().toISOString() }
                return [bumped, ...prev.filter((c) => c.id !== done.conversationId)]
              })
            },
            onError: (err) => {
              patchMessage(assistantKey, () => ({ status: 'error', error: err.message, questionStored }))
              if (err.code === 'RATE_LIMITED') {
                toast.warning(err.message, { duration: Math.max(4000, (err.retryAfterSeconds ?? 5) * 1000) })
              } else {
                toast.error(err.message)
              }
            },
          },
          controller.signal,
        )
      } catch (e) {
        if (controller.signal.aborted) {
          patchMessage(assistantKey, () => ({ status: 'stopped' }))
          if (conversationId) {
            const id = conversationId
            window.setTimeout(() => void syncFromServer(id), 400)
          }
        } else {
          const { message, retryAfter } = describe(e)
          patchMessage(assistantKey, () => ({ status: 'error', error: message, questionStored }))
          if (retryAfter) toast.warning(message, { duration: Math.max(4000, retryAfter * 1000) })
          else toast.error(message)
        }
      } finally {
        // A stream that ended without done/error (connection dropped) must not spin forever.
        setMessages((prev) =>
          prev.map((m) =>
            m.key === assistantKey && m.status === 'streaming'
              ? {
                  ...m,
                  status: m.content ? 'stopped' : 'error',
                  error: m.content ? undefined : 'The connection closed before an answer arrived.',
                  questionStored,
                }
              : m,
          ),
        )
        if (abortRef.current === controller) abortRef.current = null
        setStreaming(false)
      }
    },
    [patchMessage, syncFromServer],
  )

  const send = useCallback(
    (text: string) => {
      const trimmed = text.trim()
      if (!trimmed || abortRef.current) return
      void runTurn(trimmed, { regenerate: false })
    },
    [runTurn],
  )

  const stop = useCallback(() => abortRef.current?.abort(), [])

  /**
   * Regenerate the last answer, or retry a failed one. If the server already stored the question,
   * `regenerate: true` makes it replace the last exchange; otherwise the question is simply re-sent.
   */
  const regenerate = useCallback(() => {
    if (abortRef.current) return
    const lastUserPos = messages.map((m) => m.role).lastIndexOf('user')
    const lastUser = messages[lastUserPos]
    if (!lastUser) return
    const last = messages[messages.length - 1]
    const serverHasQuestion =
      last?.role === 'assistant' && last.status === 'error' ? Boolean(last.questionStored) : true
    setMessages((prev) => prev.slice(0, lastUserPos + 1))
    void runTurn(lastUser.content, { regenerate: serverHasQuestion, keepUserKey: lastUser.key })
  }, [messages, runTurn])

  const openConversation = useCallback(
    async (id: string) => {
      if (abortRef.current) abortRef.current.abort()
      activeIdRef.current = id
      setActiveId(id)
      setLoadingConversation(true)
      try {
        const detail = await api.getConversation(id)
        setMessages(fromDetail(detail))
      } catch (e) {
        toast.error(describe(e).message)
        setActiveId(null)
        setMessages([])
        void refreshList()
      } finally {
        setLoadingConversation(false)
      }
    },
    [refreshList],
  )

  const newChat = useCallback(() => {
    if (abortRef.current) abortRef.current.abort()
    activeIdRef.current = null
    setActiveId(null)
    setMessages([])
  }, [])

  const rename = useCallback(async (id: string, title: string) => {
    try {
      const updated = await api.renameConversation(id, title)
      setConversations((prev) => prev.map((c) => (c.id === id ? { ...c, title: updated.title } : c)))
      toast.success('Chat renamed')
    } catch (e) {
      toast.error(describe(e).message)
    }
  }, [])

  const remove = useCallback(
    async (id: string) => {
      try {
        await api.deleteConversation(id)
        setConversations((prev) => prev.filter((c) => c.id !== id))
        if (activeIdRef.current === id) newChat()
        toast.success('Chat deleted')
      } catch (e) {
        toast.error(describe(e).message)
      }
    },
    [newChat],
  )

  const rate = useCallback(
    async (message: UiMessage, rating: Rating) => {
      const id = activeIdRef.current
      if (!id || message.index === null) return
      const previous = message.feedback
      patchMessage(message.key, () => ({ feedback: rating }))
      try {
        await api.sendFeedback(id, message.index, rating)
      } catch (e) {
        patchMessage(message.key, () => ({ feedback: previous }))
        toast.error(describe(e).message)
      }
    },
    [patchMessage],
  )

  return {
    conversations,
    activeId,
    messages,
    streaming,
    loadingConversation,
    send,
    stop,
    regenerate,
    openConversation,
    newChat,
    rename,
    remove,
    rate,
  }
}
