import { Download, FileText, ListChecks, Plug, Slash, Webhook } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export const STARTER_QUESTIONS: { icon: LucideIcon; text: string }[] = [
  { icon: Download, text: 'How do I install Claude Code?' },
  { icon: FileText, text: 'What should go in CLAUDE.md for a Spring Boot project?' },
  { icon: Plug, text: 'How do I add an MCP server?' },
  { icon: Webhook, text: 'How do hooks work?' },
  { icon: Slash, text: 'How do I create a custom slash command or skill?' },
  { icon: ListChecks, text: 'How do I use plan mode?' },
]
