package com.claudecodecoach.ingestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the llms.txt index: every Markdown list item of the form
 * {@code - [Title](https://.../page.md): description} becomes a {@link DocPage}.
 */
public final class LlmsTxtParser {

    private static final Pattern LINK = Pattern.compile("^\\s*[-*]\\s+\\[([^\\]]+)]\\((https?://[^)\\s]+\\.md)\\)");

    private LlmsTxtParser() {
    }

    public static List<DocPage> parse(String llmsTxt) {
        Map<String, DocPage> pages = new LinkedHashMap<>();
        for (String line : llmsTxt.split("\\R")) {
            Matcher m = LINK.matcher(line);
            if (m.find()) {
                String title = m.group(1).trim();
                String mdUrl = m.group(2);
                String pageUrl = mdUrl.substring(0, mdUrl.length() - ".md".length());
                pages.putIfAbsent(mdUrl, new DocPage(title, mdUrl, pageUrl));
            }
        }
        return new ArrayList<>(pages.values());
    }
}
