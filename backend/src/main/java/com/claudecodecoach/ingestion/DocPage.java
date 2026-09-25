package com.claudecodecoach.ingestion;

/**
 * One page listed in llms.txt.
 *
 * @param title       page title from the index
 * @param markdownUrl the {@code .md} URL we download
 * @param pageUrl     the human-readable URL shown to users as a source link
 */
public record DocPage(String title, String markdownUrl, String pageUrl) {
}
