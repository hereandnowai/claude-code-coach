import { useRef } from 'react'
import type { ReactNode } from 'react'

import { CopyButton } from './CopyButton'

const LABELS: Record<string, string> = {
  bash: 'bash',
  sh: 'shell',
  shell: 'shell',
  zsh: 'zsh',
  powershell: 'PowerShell',
  ps1: 'PowerShell',
  json: 'JSON',
  jsonc: 'JSON',
  yaml: 'YAML',
  yml: 'YAML',
  xml: 'XML',
  java: 'Java',
  kotlin: 'Kotlin',
  groovy: 'Groovy',
  gradle: 'Gradle',
  markdown: 'Markdown',
  md: 'Markdown',
  typescript: 'TypeScript',
  ts: 'TypeScript',
  javascript: 'JavaScript',
  js: 'JavaScript',
  python: 'Python',
  toml: 'TOML',
  properties: 'properties',
  text: 'text',
}

export function CodeBlock({ language, children }: { language?: string; children: ReactNode }) {
  const preRef = useRef<HTMLPreElement>(null)
  const label = language ? (LABELS[language.toLowerCase()] ?? language) : 'code'

  return (
    <div className="group/code relative my-4 overflow-hidden rounded-xl border border-code-border bg-code text-code-foreground">
      <div className="flex h-9 items-center justify-between border-b border-white/[0.06] pr-1.5 pl-3.5">
        <span className="font-mono text-[0.72rem] text-[#9aa4b2]">{label}</span>
        <CopyButton
          getText={() => preRef.current?.innerText ?? ''}
          label="Copy code"
          className="text-[#b6bfcc] hover:bg-white/10 hover:text-white"
        />
      </div>
      <pre
        ref={preRef}
        className="[scrollbar-width:thin] [scrollbar-color:#3a4452_transparent] overflow-x-auto px-4 py-3.5 font-mono text-[0.8125rem] leading-relaxed"
      >
        {children}
      </pre>
    </div>
  )
}
