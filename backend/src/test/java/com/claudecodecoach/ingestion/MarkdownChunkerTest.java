package com.claudecodecoach.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.claudecodecoach.Fixtures;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker(700, 900, 80);

    private final DocPage mcpPage = new DocPage("Connect Claude Code to tools via MCP",
            "https://code.claude.com/docs/en/mcp.md", "https://code.claude.com/docs/en/mcp");

    @Test
    void cleansMdxAndKeepsCodeFences() {
        String cleaned = MarkdownChunker.clean(Fixtures.read("mcp.md"));

        assertThat(cleaned).doesNotContain("Documentation Index", "<Steps>", "<Step ", "</Step>", "<Warning>");
        assertThat(cleaned).contains("**Add a remote HTTP server**");
        assertThat(cleaned).contains("Warning: ");
        assertThat(cleaned).contains("claude mcp add --transport http github https://api.githubcopilot.com/mcp/");
        assertThat(cleaned).startsWith("# Connect Claude Code to tools via MCP");
    }

    @Test
    void doesNotTouchTagsInsideCodeFences() {
        String md = "# T\n\n```xml\n<Step title=\"x\">\n<dependency/>\n```\n";
        assertThat(MarkdownChunker.clean(md)).contains("<Step title=\"x\">", "<dependency/>");
    }

    @Test
    void dropsEmbeddedReactComponents() {
        String md = "# Page\n\nexport const Widget = () => {\n  return <div>## Not a heading</div>;\n};\n\n## Real section\n\nText.";
        String cleaned = MarkdownChunker.clean(md);
        assertThat(cleaned).doesNotContain("Widget", "Not a heading").contains("## Real section");
    }

    @Test
    void chunksCarryPageMetadataAndHumanUrl() {
        List<DocChunk> chunks = chunker.chunk(mcpPage, Fixtures.read("mcp.md"));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.pageTitle()).isEqualTo("Connect Claude Code to tools via MCP");
            assertThat(c.url()).isEqualTo("https://code.claude.com/docs/en/mcp").doesNotEndWith(".md");
            assertThat(c.id()).startsWith(c.url() + "#");
        });
        assertThat(String.join(" ", chunks.stream().map(DocChunk::headings).toList())).contains("Add an MCP server",
                "MCP installation scopes");
    }

    @Test
    void splitsOnHeadingsWithHeadingPath() {
        StringBuilder md = new StringBuilder("# Guide\n\n");
        for (int s = 1; s <= 3; s++) {
            md.append("## Section ").append(s).append("\n\n");
            md.append("### Detail ").append(s).append("\n\n");
            md.append("word ".repeat(700)).append("\n\n");
        }
        List<DocChunk> chunks = chunker.chunk(new DocPage("Guide", "u.md", "u"), md.toString());

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks).anySatisfy(c -> assertThat(c.headingPath()).isEqualTo("Guide > Section 2 > Detail 2"));
    }

    @Test
    void keepsChunksWithinMaxSizeAndOverlapsSplitSections() {
        String para = "Claude Code reads your Maven build and runs the tests with mvnw verify. ";
        StringBuilder md = new StringBuilder("# Big\n\n## Long section\n\n");
        for (int i = 0; i < 60; i++) {
            md.append("Paragraph ").append(i).append(": ").append(para.repeat(3)).append("\n\n");
        }
        List<DocChunk> chunks = chunker.chunk(new DocPage("Big", "b.md", "b"), md.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(MarkdownChunker.estimateTokens(c.text())).isLessThanOrEqualTo(900));
        // overlap: the last paragraph of one piece starts the next one
        String lastParagraphOfFirst = chunks.get(0).text().substring(chunks.get(0).text().lastIndexOf("Paragraph "));
        assertThat(chunks.get(1).text()).startsWith(lastParagraphOfFirst.strip());
    }

    @Test
    void cutsMinifiedSingleLinesThatExceedTheMaximum() {
        String md = "# Min\n\n" + "x".repeat(20_000);
        List<DocChunk> chunks = chunker.chunk(new DocPage("Min", "m.md", "m"), md);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(900 * 4 + 10));
    }
}
