import { memo } from 'react'
import type { ComponentPropsWithoutRef } from 'react'
import ReactMarkdown from 'react-markdown'
import type { Components } from 'react-markdown'
import rehypeHighlight from 'rehype-highlight'
import remarkGfm from 'remark-gfm'

import { cn } from '@/lib/utils'

import { CodeBlock } from './CodeBlock'

const LANGUAGE = /language-([\w+-]+)/

function languageOf(children: unknown): string | undefined {
  if (children && typeof children === 'object' && 'props' in children) {
    const props = (children as { props?: { className?: unknown } }).props
    const match = typeof props?.className === 'string' ? LANGUAGE.exec(props.className) : null
    return match?.[1]
  }
  return undefined
}

const components: Components = {
  pre: ({ children }) => <CodeBlock language={languageOf(children)}>{children}</CodeBlock>,
  a: ({ href, children, ...rest }: ComponentPropsWithoutRef<'a'>) => {
    // Docs links are site-relative (/docs/en/...): point them at the real docs site.
    const url = href?.startsWith('/docs/') ? `https://code.claude.com${href}` : href
    return (
      <a href={url} target="_blank" rel="noopener noreferrer" {...rest}>
        {children}
      </a>
    )
  },
}

interface MarkdownProps {
  content: string
  streaming?: boolean
}

/** Markdown answer renderer. Raw HTML in the model output is never rendered (no rehype-raw). */
export const Markdown = memo(function Markdown({ content, streaming = false }: MarkdownProps) {
  return (
    <div className={cn('prose-answer', streaming && 'streaming-caret')}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[[rehypeHighlight, { detect: false }]]}
        components={components}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
})
