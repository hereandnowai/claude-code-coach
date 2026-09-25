import { ArrowUpRight, BookOpen } from 'lucide-react'

import type { SourceRef } from '@/lib/types'

function pathOf(url: string): string {
  try {
    return new URL(url).pathname.replace(/^\/docs\/en\//, '')
  } catch {
    return url
  }
}

export function SourceChips({ sources }: { sources: SourceRef[] }) {
  if (sources.length === 0) return null
  return (
    <nav aria-label="Sources" className="mt-4">
      <h3 className="mb-2 text-xs font-medium text-muted-foreground">From the Claude Code docs</h3>
      <ul className="flex flex-wrap gap-2">
        {sources.map((s) => (
          <li key={s.url}>
            <a
              href={s.url}
              target="_blank"
              rel="noopener noreferrer"
              className="group/chip flex max-w-72 items-center gap-2 rounded-lg border bg-card py-1.5 pr-2 pl-2.5 text-sm transition-colors hover:border-primary/50 hover:bg-accent focus-visible:ring-ring/50"
            >
              <BookOpen aria-hidden className="size-3.5 shrink-0 text-primary" />
              <span className="min-w-0">
                <span className="block truncate font-medium">{s.title}</span>
                <span className="block truncate font-mono text-[0.7rem] text-muted-foreground">
                  {pathOf(s.url)}
                </span>
              </span>
              <ArrowUpRight
                aria-hidden
                className="size-3.5 shrink-0 text-muted-foreground transition-colors group-hover/chip:text-primary"
              />
              <span className="sr-only">(opens in a new tab)</span>
            </a>
          </li>
        ))}
      </ul>
    </nav>
  )
}
