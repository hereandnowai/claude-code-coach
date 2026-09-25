import { describe, expect, it } from 'vitest'

import { createSseParser } from './sse'
import type { SseMessage } from './sse'

function parseAll(chunks: string[]): SseMessage[] {
  const out: SseMessage[] = []
  const parser = createSseParser((m) => out.push(m))
  chunks.forEach((c) => parser.feed(c))
  parser.end()
  return out
}

describe('createSseParser', () => {
  it('parses named events the way Spring MVC SseEmitter writes them', () => {
    const body = 'event:meta\ndata:{"conversationId":"c1"}\n\nevent:token\ndata:{"text":"Hi"}\n\n'
    expect(parseAll([body])).toEqual([
      { event: 'meta', data: '{"conversationId":"c1"}' },
      { event: 'token', data: '{"text":"Hi"}' },
    ])
  })

  it('reassembles events split across arbitrary chunk boundaries', () => {
    const body = 'event: token\ndata: {"text":"Hello"}\n\nevent: done\ndata: {}\n\n'
    const chunks = body.split('') // one character at a time
    expect(parseAll(chunks)).toEqual([
      { event: 'token', data: '{"text":"Hello"}' },
      { event: 'done', data: '{}' },
    ])
  })

  it('handles CRLF line endings, including a CRLF split between chunks', () => {
    expect(parseAll(['event: token\r', '\ndata: a\r\n\r', '\n'])).toEqual([{ event: 'token', data: 'a' }])
  })

  it('joins multi-line data and ignores comments', () => {
    expect(parseAll([': keep-alive\n', 'data: line 1\ndata: line 2\n\n'])).toEqual([
      { event: 'message', data: 'line 1\nline 2' },
    ])
  })

  it('flushes a final event without a trailing blank line', () => {
    expect(parseAll(['event: done\ndata: {"messageIndex":1}'])).toEqual([
      { event: 'done', data: '{"messageIndex":1}' },
    ])
  })

  it('ignores events with no data', () => {
    expect(parseAll(['event: ping\n\n'])).toEqual([])
  })
})
