export interface SseMessage {
  event: string
  data: string
}

/**
 * Incremental Server-Sent Events parser (WHATWG format) for fetch() response bodies.
 * Feed it decoded text chunks in any size; it calls `onMessage` once per complete event.
 * Handles CRLF/LF/CR line endings, multi-line `data:`, comments and a missing space after the colon.
 */
export function createSseParser(onMessage: (message: SseMessage) => void) {
  let buffer = ''
  let eventName = ''
  let dataLines: string[] = []

  const dispatch = () => {
    if (dataLines.length > 0) {
      onMessage({ event: eventName || 'message', data: dataLines.join('\n') })
    }
    eventName = ''
    dataLines = []
  }

  const processLine = (line: string) => {
    if (line === '') {
      dispatch()
      return
    }
    if (line.startsWith(':')) return
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') eventName = value
    else if (field === 'data') dataLines.push(value)
    // `id` and `retry` are not used by this app
  }

  return {
    feed(chunk: string) {
      buffer += chunk
      // Split on any line ending, keeping a trailing partial line (and a lone trailing \r,
      // which may be the first half of a \r\n split across chunks) in the buffer.
      const lines = buffer.split(/\r\n|\n|\r(?!$)/)
      buffer = lines.pop() ?? ''
      for (const line of lines) processLine(line)
    },
    /** Flush a final event that was not followed by a blank line. */
    end() {
      if (buffer !== '') {
        processLine(buffer.replace(/\r$/, ''))
        buffer = ''
      }
      dispatch()
    },
  }
}
