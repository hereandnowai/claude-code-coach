package com.claudecodecoach.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import com.claudecodecoach.Fixtures;

class LuceneDocumentRetrieverTest {

    @TempDir
    static Path dir;

    static LuceneIndexService index;
    static LuceneDocumentRetriever retriever;

    @BeforeAll
    static void buildIndex() throws IOException {
        index = Fixtures.index(dir);
        retriever = new LuceneDocumentRetriever(index, Fixtures.retrievalSettings(dir));
    }

    @AfterAll
    static void close() throws IOException {
        index.close();
    }

    private static String topTitle(String question) {
        List<Document> docs = retriever.retrieve(new Query(question));
        assertThat(docs).as("results for '%s'", question).isNotEmpty();
        return (String) docs.getFirst().getMetadata().get(LuceneDocumentRetriever.META_TITLE);
    }

    @Test
    void findsTheRightPageForTypicalQuestions() {
        assertThat(topTitle("How do I add an MCP server in Claude Code?")).isEqualTo("Connect Claude Code to tools via MCP");
        assertThat(topTitle("How do I install Claude Code?")).isEqualTo("Quickstart");
        assertThat(topTitle("How do hooks work?")).isEqualTo("Automate actions with hooks");
        assertThat(topTitle("What should go in CLAUDE.md for a Spring Boot project?"))
            .isEqualTo("How Claude remembers your project");
    }

    @Test
    void questionWordsAndProductNameDoNotDriveRanking() {
        // Every page mentions "Claude Code"; the answer must come from the topical word.
        assertThat(topTitle("What is PreToolUse?")).isEqualTo("Automate actions with hooks");
    }

    @Test
    void returnsSourceMetadataWithHumanReadableUrl() {
        Document top = retriever.retrieve(new Query("claude mcp add transport")).getFirst();
        assertThat(top.getMetadata()).containsEntry(LuceneDocumentRetriever.META_URL, Fixtures.BASE + "mcp")
            .containsKey(LuceneDocumentRetriever.META_HEADING_PATH);
        assertThat(top.getScore()).isPositive();
    }

    @Test
    void returnsNothingForOffTopicQuestions() {
        assertThat(retriever.retrieve(new Query("Give me a recipe for chocolate chip cookies"))).isEmpty();
        assertThat(retriever.retrieve(new Query("Who won the football world cup?"))).isEmpty();
        assertThat(index.search("   ", 8)).isEmpty();
        assertThat(retriever.retrieve(new Query("hello thanks"))).isEmpty();
    }

    @Test
    void handlesQueryParserSyntaxCharacters() {
        assertThat(retriever.retrieve(new Query("claude -p \"hooks\" AND (PreToolUse) [x] ~ * ?"))).isNotEmpty();
    }

    @Test
    void reportsStats() {
        LuceneIndexService.Stats stats = index.stats();
        assertThat(stats.ready()).isTrue();
        assertThat(stats.pages()).isEqualTo(4);
        assertThat(stats.chunks()).isGreaterThanOrEqualTo(4);
        assertThat(stats.indexedAt()).isNotNull();
    }
}
