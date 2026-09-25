package com.claudecodecoach.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All application-specific settings live under the {@code coach.*} prefix in application.yml.
 * Model settings (model name, temperature, tokens, thinking) stay under Spring AI's own
 * {@code spring.ai.google.genai.chat.*} prefix so there is exactly one place the model is named.
 */
@ConfigurationProperties("coach")
public record CoachProperties(
        @DefaultValue Model model,
        @DefaultValue Retrieval retrieval,
        @DefaultValue Ingestion ingestion,
        @DefaultValue Chat chat,
        @DefaultValue Web web,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue("") String adminToken) {

    /** thinkingLevel: LOW, HIGH or NONE (NONE sends no thinking config; Google currently rejects the others). */
    public record Model(@DefaultValue("NONE") String thinkingLevel) {
    }

    public record Retrieval(
            @DefaultValue("./data/index") Path indexDir,
            @DefaultValue("8") int topK,
            @DefaultValue("3") int maxChunksPerPage,
            /* Absolute BM25 floor; hits below it are treated as noise. */
            @DefaultValue("3.0") float minScore,
            /* Hits scoring below this fraction of the best hit are dropped. */
            @DefaultValue("0.25") float relativeCutoff) {
    }

    public record Ingestion(
            @DefaultValue("https://code.claude.com/docs/llms.txt") String llmsTxtUrl,
            /* Only index pages under this prefix (llms.txt also links translated indexes). */
            @DefaultValue("https://code.claude.com/docs/en/") String pageUrlPrefix,
            /* Very large pages (the changelog) are truncated to their first N characters. */
            @DefaultValue("150000") int maxPageChars,
            @DefaultValue("0 0 3 * * *") String cron,
            @DefaultValue("true") boolean onStartupIfMissing,
            @DefaultValue("6") int fetchConcurrency,
            @DefaultValue("20s") Duration fetchTimeout,
            @DefaultValue("700") int targetChunkTokens,
            @DefaultValue("900") int maxChunkTokens,
            @DefaultValue("80") int overlapTokens) {
    }

    public record Chat(
            @DefaultValue("4000") int maxMessageLength,
            /* Number of prior messages (user + assistant) sent to the model with each turn. */
            @DefaultValue("10") int historyWindow,
            /* Rewrite follow-up questions into standalone search queries with the same model. */
            @DefaultValue("true") boolean queryRewrite,
            @DefaultValue("60") int titleMaxLength,
            @DefaultValue("5m") Duration streamTimeout,
            /* Fail the stream if the model sends nothing for this long (includes time to first token). */
            @DefaultValue("90s") Duration idleTimeout) {
    }

    public record Web(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
    }

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10") int capacity,
            @DefaultValue("1m") Duration refillPeriod) {
    }
}
