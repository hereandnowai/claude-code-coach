package com.claudecodecoach.ingestion;

/**
 * A retrievable slice of one documentation page.
 *
 * @param id          stable id: page URL + "#" + chunk ordinal
 * @param pageTitle   title of the page the chunk came from
 * @param url         human-readable page URL (not the .md URL)
 * @param headingPath heading breadcrumb of the chunk's first section, e.g. "Hooks > Configuration"
 * @param headings    every heading contained in the chunk, space separated (indexed with a boost)
 * @param text        chunk body in Markdown
 */
public record DocChunk(String id, String pageTitle, String url, String headingPath, String headings, String text) {
}
