import { ArrowDown, PanelLeftOpen, SquarePen } from 'lucide-react'
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

import { Composer } from '@/components/chat/Composer'
import type { ComposerHandle } from '@/components/chat/Composer'
import { EmptyState } from '@/components/chat/EmptyState'
import { MessageItem } from '@/components/chat/MessageItem'
import { Sidebar } from '@/components/layout/Sidebar'
import { ThemeToggle } from '@/components/layout/ThemeToggle'
import { Button } from '@/components/ui/button'
import { Toaster } from '@/components/ui/sonner'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { useChat } from '@/hooks/useChat'

const DESKTOP = '(min-width: 768px)'

export default function App() {
  const chat = useChat()
  const [sidebarOpen, setSidebarOpen] = useState(() => window.matchMedia(DESKTOP).matches)
  const composerRef = useRef<ComposerHandle>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const [pinnedToBottom, setPinnedToBottom] = useState(true)

  const isMobile = () => !window.matchMedia(DESKTOP).matches
  const closeOnMobile = () => {
    if (isMobile()) setSidebarOpen(false)
  }

  const activeTitle = chat.conversations.find((c) => c.id === chat.activeId)?.title

  // Follow the stream while the reader is at the bottom; stop following once they scroll up.
  const onScroll = useCallback(() => {
    const el = scrollRef.current
    if (!el) return
    setPinnedToBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 80)
  }, [])

  useLayoutEffect(() => {
    const el = scrollRef.current
    if (el && pinnedToBottom && chat.messages.length > 0) el.scrollTop = el.scrollHeight
  }, [chat.messages, pinnedToBottom])

  const scrollToBottom = () => {
    const el = scrollRef.current
    el?.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    setPinnedToBottom(true)
  }

  const send = (text: string) => {
    setPinnedToBottom(true)
    chat.send(text)
  }

  useEffect(() => {
    if (!chat.streaming) composerRef.current?.focus()
  }, [chat.streaming, chat.activeId])

  const lastIndex = chat.messages.length - 1

  return (
    <TooltipProvider delayDuration={400}>
      <a
        href="#composer"
        className="sr-only z-50 rounded-md bg-primary px-3 py-2 text-primary-foreground focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        Skip to message box
      </a>
      <div className="flex h-dvh overflow-hidden">
        <Sidebar
          open={sidebarOpen}
          conversations={chat.conversations}
          activeId={chat.activeId}
          onClose={() => setSidebarOpen(false)}
          onNewChat={() => {
            chat.newChat()
            closeOnMobile()
          }}
          onOpen={(id) => {
            setPinnedToBottom(true)
            void chat.openConversation(id)
            closeOnMobile()
          }}
          onRename={chat.rename}
          onDelete={chat.remove}
        />

        <div className="flex min-w-0 flex-1 flex-col">
          <header className="flex h-14 shrink-0 items-center gap-1 px-3 sm:px-4">
            {!sidebarOpen && (
              <>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => setSidebarOpen(true)}
                      aria-label="Open sidebar"
                      aria-controls="sidebar"
                      aria-expanded={false}
                    >
                      <PanelLeftOpen aria-hidden />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>Open sidebar</TooltipContent>
                </Tooltip>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="ghost" size="icon" onClick={chat.newChat} aria-label="New chat">
                      <SquarePen aria-hidden />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>New chat</TooltipContent>
                </Tooltip>
              </>
            )}
            <p className="min-w-0 truncate px-2 text-sm font-medium" aria-live="polite">
              {activeTitle ?? ''}
            </p>
            <div className="ml-auto">
              <ThemeToggle />
            </div>
          </header>

          <main className="relative flex min-h-0 flex-1 flex-col">
            <div
              ref={scrollRef}
              onScroll={onScroll}
              className="min-h-0 flex-1 [scrollbar-gutter:stable] overflow-x-hidden overflow-y-auto"
            >
              {chat.messages.length === 0 && !chat.loadingConversation ? (
                <EmptyState onPick={send} />
              ) : (
                <ol
                  aria-label="Conversation"
                  aria-busy={chat.streaming || chat.loadingConversation}
                  className="mx-auto flex w-full max-w-[760px] flex-col gap-8 px-4 pt-6 pb-10 sm:px-6"
                >
                  {chat.messages.map((m, i) => (
                    <li key={m.key}>
                      <MessageItem
                        message={m}
                        isLast={i === lastIndex}
                        busy={chat.streaming}
                        onRate={(msg, rating) => void chat.rate(msg, rating)}
                        onRegenerate={chat.regenerate}
                      />
                    </li>
                  ))}
                </ol>
              )}
            </div>

            {!pinnedToBottom && chat.messages.length > 0 && (
              <Button
                variant="outline"
                size="icon"
                onClick={scrollToBottom}
                aria-label="Scroll to latest message"
                className="absolute bottom-36 left-1/2 -translate-x-1/2 rounded-full bg-card shadow-[var(--shadow-soft)]"
              >
                <ArrowDown aria-hidden />
              </Button>
            )}

            <div className="shrink-0 bg-background/85 pb-[max(env(safe-area-inset-bottom),0.75rem)] backdrop-blur supports-[backdrop-filter]:bg-background/70">
              <div className="mx-auto w-full max-w-[760px] px-3 pt-2 sm:px-6">
                <Composer ref={composerRef} streaming={chat.streaming} onSend={send} onStop={chat.stop} />
                <p className="mt-2 text-center text-[0.72rem] text-muted-foreground">
                  Answers are drawn from the Claude Code docs. Check commands before you run them.
                </p>
              </div>
            </div>
          </main>
        </div>
      </div>
      <Toaster position="top-center" richColors closeButton />
    </TooltipProvider>
  )
}
