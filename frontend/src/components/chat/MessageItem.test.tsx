import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { TooltipProvider } from '@/components/ui/tooltip'
import type { UiMessage } from '@/hooks/useChat'

import { MessageItem } from './MessageItem'

function assistant(overrides: Partial<UiMessage> = {}): UiMessage {
  return {
    key: 'k1',
    index: 1,
    role: 'assistant',
    content: '',
    sources: [],
    feedback: null,
    status: 'done',
    ...overrides,
  }
}

function renderMessage(message: UiMessage, props: { onRate?: () => void; onRegenerate?: () => void } = {}) {
  return render(
    <TooltipProvider>
      <MessageItem
        message={message}
        isLast
        busy={false}
        onRate={props.onRate ?? vi.fn()}
        onRegenerate={props.onRegenerate ?? vi.fn()}
      />
    </TooltipProvider>,
  )
}

describe('MessageItem', () => {
  it('renders Markdown with a highlighted, copyable code block', async () => {
    renderMessage(
      assistant({
        content:
          'Add a server:\n\n```bash\nclaude mcp add --transport http github https://example.com/mcp\n```',
      }),
    )
    expect(await screen.findByText('Add a server:')).toBeInTheDocument()
    expect(await screen.findByText('bash')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy code' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy answer' })).toBeInTheDocument()
    expect(document.querySelector('code.hljs.language-bash')).not.toBeNull()
  })

  it('never renders raw HTML from the model', async () => {
    renderMessage(
      assistant({ content: 'Hi <img src=x onerror="alert(1)"> <script>alert(2)</script>\n\nDone.' }),
    )
    await waitFor(() => expect(document.querySelector('.prose-answer')).not.toBeNull())
    expect(document.querySelector('img')).toBeNull()
    expect(document.querySelector('script')).toBeNull()
  })

  it('shows source links as chips that open in a new tab', () => {
    renderMessage(
      assistant({
        content: 'Use `claude mcp add`.',
        sources: [{ title: 'Connect to MCP servers', url: 'https://code.claude.com/docs/en/mcp' }],
      }),
    )
    const link = screen.getByRole('link', { name: /Connect to MCP servers/ })
    expect(link).toHaveAttribute('href', 'https://code.claude.com/docs/en/mcp')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
  })

  it('shows a typing indicator while waiting for the first token', () => {
    renderMessage(assistant({ status: 'streaming', index: null }))
    expect(screen.getByRole('status', { name: /looking through the docs/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Copy answer' })).toBeNull()
  })

  it('sends thumbs up / down feedback and reflects the pressed state', async () => {
    const onRate = vi.fn()
    const message = assistant({ content: 'Answer', feedback: 'UP' })
    renderMessage(message, { onRate })
    expect(screen.getByRole('button', { name: 'Good answer' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Bad answer' }))
    expect(onRate).toHaveBeenCalledWith(message, 'DOWN')
  })

  it('offers a retry when an answer failed', async () => {
    const onRegenerate = vi.fn()
    renderMessage(assistant({ status: 'error', error: 'Gemma is busy. Try again in 30 seconds.' }), {
      onRegenerate,
    })
    expect(screen.getByRole('alert')).toHaveTextContent('Gemma is busy')
    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(onRegenerate).toHaveBeenCalled()
  })

  it('renders user messages as plain text', () => {
    render(
      <TooltipProvider>
        <MessageItem
          message={{ ...assistant(), role: 'user', content: '**not bold**' }}
          isLast={false}
          busy={false}
          onRate={vi.fn()}
          onRegenerate={vi.fn()}
        />
      </TooltipProvider>,
    )
    expect(screen.getByText('**not bold**')).toBeInTheDocument()
  })
})
