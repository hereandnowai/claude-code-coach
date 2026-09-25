import { describe, expect, it } from 'vitest'

import { withoutSourcesSection } from './answer'

describe('withoutSourcesSection', () => {
  it('removes a trailing bold Sources list', () => {
    const md = 'Run `claude mcp list`.\n\n**Sources**\n- Connect to MCP servers\n- Settings\n'
    expect(withoutSourcesSection(md)).toBe('Run `claude mcp list`.')
  })

  it('removes heading-style and colon variants', () => {
    expect(withoutSourcesSection('Answer.\n\n## Sources\n* Hooks reference')).toBe('Answer.')
    expect(withoutSourcesSection('Answer.\n\nSources:\n1. Hooks reference')).toBe('Answer.')
  })

  it('leaves answers without a trailing list untouched', () => {
    const md = 'See the **Sources** tab in settings.\n\n- one\n- two'
    expect(withoutSourcesSection(md)).toBe(md)
  })

  it('does not remove a Sources list followed by more prose', () => {
    const md = 'A\n\n**Sources**\n- Hooks\n\nMore text after.'
    expect(withoutSourcesSection(md)).toBe(md)
  })
})
