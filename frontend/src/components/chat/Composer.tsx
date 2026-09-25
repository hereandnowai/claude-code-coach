import { ArrowUp, Square } from 'lucide-react'
import { forwardRef, useImperativeHandle, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export const MAX_MESSAGE_LENGTH = 4000
const COUNTER_FROM = 3400
const MAX_TEXTAREA_PX = 220

export interface ComposerHandle {
  focus: () => void
}

interface ComposerProps {
  streaming: boolean
  onSend: (text: string) => void
  onStop: () => void
}

export const Composer = forwardRef<ComposerHandle, ComposerProps>(function Composer(
  { streaming, onSend, onStop },
  ref,
) {
  const [value, setValue] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useImperativeHandle(ref, () => ({ focus: () => textareaRef.current?.focus() }), [])

  // Auto-grow: reset to auto, then fit the content up to a cap.
  useLayoutEffect(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, MAX_TEXTAREA_PX)}px`
    el.style.overflowY = el.scrollHeight > MAX_TEXTAREA_PX ? 'auto' : 'hidden'
  }, [value])

  const length = value.length
  const tooLong = length > MAX_MESSAGE_LENGTH
  const canSend = value.trim().length > 0 && !tooLong && !streaming

  const submit = () => {
    if (!canSend) return
    onSend(value)
    setValue('')
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
      className="relative rounded-2xl border bg-card shadow-[var(--shadow-soft)] transition-[border-color,box-shadow] focus-within:border-primary/60 focus-within:ring-4 focus-within:ring-primary/15"
    >
      <label htmlFor="composer" className="sr-only">
        Ask a question about Claude Code
      </label>
      <textarea
        id="composer"
        ref={textareaRef}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={onKeyDown}
        rows={1}
        placeholder="Ask about Claude Code…"
        aria-describedby="composer-hint composer-count"
        aria-invalid={tooLong}
        className="block max-h-[220px] w-full resize-none bg-transparent px-4 pt-3.5 pb-12 text-[0.9375rem] leading-relaxed outline-none placeholder:text-muted-foreground focus-visible:outline-none"
      />
      <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-3 pr-2.5 pb-2.5 pl-4">
        <p id="composer-hint" className="hidden text-xs text-muted-foreground sm:block">
          <kbd className="font-sans">Enter</kbd> to send, <kbd className="font-sans">Shift + Enter</kbd> for a
          new line
        </p>
        <div className="ml-auto flex items-center gap-3">
          <span
            id="composer-count"
            aria-live="polite"
            className={cn(
              'font-mono text-xs tabular-nums',
              length < COUNTER_FROM && 'sr-only',
              tooLong ? 'font-medium text-destructive' : 'text-muted-foreground',
            )}
          >
            {length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}
          </span>
          {streaming ? (
            <Button
              type="button"
              size="icon"
              variant="secondary"
              onClick={onStop}
              aria-label="Stop generating"
              className="rounded-full"
            >
              <Square aria-hidden className="size-3.5 fill-current" />
            </Button>
          ) : (
            <Button
              type="submit"
              size="icon"
              disabled={!canSend}
              aria-label="Send message"
              className="rounded-full"
            >
              <ArrowUp aria-hidden />
            </Button>
          )}
        </div>
      </div>
    </form>
  )
})
