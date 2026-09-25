package com.claudecodecoach.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.claudecodecoach.Fixtures;
import com.claudecodecoach.config.CoachProperties;
import com.claudecodecoach.retrieval.LuceneIndexService;

class IngestionServiceTest {

    @TempDir
    Path dir;

    private CoachProperties.Ingestion settings() {
        return new CoachProperties.Ingestion("https://code.claude.com/docs/llms.txt", Fixtures.BASE, 150_000,
                "0 0 3 * * *", false, 2, Duration.ofSeconds(5), 700, 900, 80);
    }

    @Test
    void parsesLlmsTxtIntoPagesWithHumanUrls() {
        var pages = LlmsTxtParser.parse(Fixtures.read("llms.txt"));
        assertThat(pages).hasSize(5);
        assertThat(pages.getFirst()).isEqualTo(new DocPage("Quickstart", Fixtures.BASE + "quickstart.md",
                Fixtures.BASE + "quickstart"));
    }

    @Test
    void ingestsEnglishPagesIntoTheIndex() throws IOException {
        try (LuceneIndexService index = new LuceneIndexService(dir)) {
            IngestionService ingestion = new IngestionService(settings(), Fixtures.fetcher(), index);

            ingestion.runNow();

            assertThat(index.isReady()).isTrue();
            assertThat(index.stats().pages()).isEqualTo(4); // the translated index link is skipped
            assertThat(ingestion.status().lastError()).isNull();
            assertThat(ingestion.status().lastPagesFetched()).isEqualTo(4);
        }
    }

    @Test
    void keepsTheExistingIndexWhenMostPagesFail() throws IOException {
        try (LuceneIndexService index = Fixtures.index(dir)) {
            int before = index.stats().chunks();
            DocsFetcher mostlyBroken = url -> {
                if (url.endsWith("llms.txt") || url.endsWith("quickstart.md")) {
                    return Fixtures.fetcher().fetch(url);
                }
                throw new IOException("GET " + url + " returned HTML instead of Markdown");
            };
            IngestionService ingestion = new IngestionService(settings(), mostlyBroken, index);

            ingestion.runNow();

            assertThat(ingestion.status().lastError()).contains("keeping the existing index");
            assertThat(index.stats().chunks()).isEqualTo(before);
        }
    }
}
