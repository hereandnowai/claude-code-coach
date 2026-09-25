package com.claudecodecoach;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.claudecodecoach.config.CoachProperties;
import com.claudecodecoach.ingestion.DocChunk;
import com.claudecodecoach.ingestion.DocPage;
import com.claudecodecoach.ingestion.DocsFetcher;
import com.claudecodecoach.ingestion.MarkdownChunker;
import com.claudecodecoach.retrieval.LuceneIndexService;

/** Test fixtures: a tiny copy of the docs site served from src/test/resources/fixtures. */
public final class Fixtures {

    public static final String BASE = "https://code.claude.com/docs/en/";

    public static final Map<String, String> PAGES = Map.of(
            "Quickstart", "quickstart",
            "Connect Claude Code to tools via MCP", "mcp",
            "Automate actions with hooks", "hooks-guide",
            "How Claude remembers your project", "memory");

    private static final Map<String, String> FILES = Map.of(
            "quickstart", "quickstart.md", "mcp", "mcp.md", "hooks-guide", "hooks.md", "memory", "memory.md");

    private Fixtures() {
    }

    public static String read(String file) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + file)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Serves llms.txt and the fixture pages by URL, like the real docs site. */
    public static DocsFetcher fetcher() {
        return url -> {
            if (url.endsWith("/llms.txt")) {
                return read("llms.txt");
            }
            for (Map.Entry<String, String> e : FILES.entrySet()) {
                if (url.equals(BASE + e.getKey() + ".md")) {
                    return read(e.getValue());
                }
            }
            throw new IOException("404 " + url);
        };
    }

    public static List<DocChunk> chunks() {
        MarkdownChunker chunker = new MarkdownChunker(700, 900, 80);
        List<DocChunk> chunks = new ArrayList<>();
        PAGES.forEach((title, slug) -> chunks
            .addAll(chunker.chunk(new DocPage(title, BASE + slug + ".md", BASE + slug), read(FILES.get(slug)))));
        return chunks;
    }

    public static LuceneIndexService index(Path dir) throws IOException {
        LuceneIndexService index = new LuceneIndexService(dir);
        index.rebuild(chunks(), PAGES.size());
        return index;
    }

    /** BM25 scores scale with corpus size; a 4-page corpus needs a proportionally lower absolute floor. */
    public static CoachProperties.Retrieval retrievalSettings(Path dir) {
        return new CoachProperties.Retrieval(dir, 8, 3, 0.5f, 0.25f);
    }
}
