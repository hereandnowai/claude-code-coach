/**
 * The model ends grounded answers with a "Sources" list of page titles (the backend uses it to pick
 * which source chips to show). When chips are shown, that trailing list is redundant on screen.
 */
const SOURCES_HEADING =
  /\n[ \t]*(?:#{1,6}[ \t]*)?(?:\*\*|__)?Sources(?:\*\*|__)?:?[ \t]*\n(?:[ \t]*(?:[-*+]|\d+\.)[ \t].*(?:\n|$))+\s*$/i

export function withoutSourcesSection(markdown: string): string {
  return markdown.replace(SOURCES_HEADING, '\n').trimEnd()
}
