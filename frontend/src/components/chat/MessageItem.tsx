import { AlertCircle, RotateCcw, ThumbsDown, ThumbsUp } from 'lucide-react'
import { Suspense, lazy } from 'react'

import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import type { UiMessage } from '@/hooks/useChat'
import { withoutSourcesSection } from '@/lib/answer'
import type { Rating } from '@/lib/types'
import { cn } from '@/lib/utils'

import { CoachMark } from '../brand/CoachMark'
import { CopyButton } from './CopyButton'
import { SourceChips } from './SourceChips'

// Markdown + highlight.js are the heaviest part of the bundle; load them after the shell renders.
const Markdown = lazy(() => import('./Markdown').then((m) => ({ default: m.Markdown })))

export function TypingIndicator() {
  return (
    <div role="status" aria-label="Looking through the docs" className="flex h-7 items-center gap-1">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="size-1.5 rounded-full bg-primary"
          style={{ animation: `dot-bounce 1.2s ${i * 0.15}s infinite ease-in-out` }}
        />
      ))}
      <span className="ml-2 text-sm text-muted-foreground">Looking through the docs</span>
    </div>
  )
}

function UserMessage({ message }: { message: UiMessage }) {
  return (
    <div className="flex justify-end">
      <div className="max-w-[85%] rounded-2xl rounded-br-md bg-user-bubble px-4 py-2.5 text-[0.9375rem] leading-relaxed whitespace-pre-wrap sm:max-w-[75%]">
        <span className="sr-only">You said: </span>
        {message.content}
      </div>
    </div>
  )
}

interface AssistantMessageProps {
  message: UiMessage
  isLast: boolean
  busy: boolean
  onRate: (message: UiMessage, rating: Rating) => void
  onRegenerate: () => void
}

function RateButton({
  rating,
  message,
  onRate,
}: {
  rating: Rating
  message: UiMessage
  onRate: AssistantMessageProps['onRate']
}) {
  const active = message.feedback === rating
  const Icon = rating === 'UP' ? ThumbsUp : ThumbsDown
  const label = rating === 'UP' ? 'Good answer' : 'Bad answer'
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={label}
          aria-pressed={active}
          disabled={message.index === null}
          onClick={() => onRate(message, rating)}
          className={cn('text-muted-foreground', active && 'bg-accent text-primary')}
        >
          <Icon aria-hidden className={cn(active && 'fill-current')} />
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  )
}

function AssistantMessage({ message, isLast, busy, onRate, onRegenerate }: AssistantMessageProps) {
  const streaming = message.status === 'streaming'
  const failed = message.status === 'error'
  const shown =
    !streaming && message.sources.length > 0 ? withoutSourcesSection(message.content) : message.content

  return (
    <div className="flex gap-3 sm:gap-4">
      <CoachMark className="mt-0.5 size-7 shrink-0" />
      <div className="min-w-0 flex-1">
        <span className="sr-only">Coach answered: </span>
        {streaming && message.content === '' ? (
          <TypingIndicator />
        ) : (
          message.content && (
            <Suspense
              fallback={
                <p className="text-[0.9375rem] leading-relaxed whitespace-pre-wrap">{message.content}</p>
              }
            >
              <Markdown content={shown} streaming={streaming} />
            </Suspense>
          )
        )}

        {message.status === 'stopped' && (
          <p className="mt-2 text-sm text-muted-foreground">Stopped. The partial answer was kept.</p>
        )}

        {failed && (
          <div
            role="alert"
            className="mt-1 flex items-start gap-3 rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-foreground"
          >
            <AlertCircle aria-hidden className="mt-0.5 size-4 shrink-0 text-destructive" />
            <div className="flex-1">
              <p>{message.error ?? 'The answer could not be generated.'}</p>
              {isLast && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="mt-2"
                  onClick={onRegenerate}
                  disabled={busy}
                >
                  <RotateCcw aria-hidden />
                  Try again
                </Button>
              )}
            </div>
          </div>
        )}

        {!streaming && <SourceChips sources={message.sources} />}

        {!streaming && !failed && message.content && (
          <div className="mt-3 -ml-1.5 flex items-center gap-0.5" aria-label="Answer actions" role="group">
            <CopyButton getText={() => message.content} label="Copy answer" />
            <RateButton rating="UP" message={message} onRate={onRate} />
            <RateButton rating="DOWN" message={message} onRate={onRate} />
            {isLast && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label="Regenerate answer"
                    className="text-muted-foreground"
                    onClick={onRegenerate}
                    disabled={busy}
                  >
                    <RotateCcw aria-hidden />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Regenerate answer</TooltipContent>
              </Tooltip>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

export function MessageItem(props: AssistantMessageProps) {
  return props.message.role === 'user' ? (
    <UserMessage message={props.message} />
  ) : (
    <AssistantMessage {...props} />
  )
}
