package com.claudecodecoach.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.claudecodecoach.ingestion.IngestionService;
import com.claudecodecoach.retrieval.LuceneIndexService;

/**
 * Health components shown at {@code GET /api/health}: {@code index} (document/chunk counts,
 * ingestion state) and {@code model} (model name and provider). The API key is never included.
 */
@Configuration(proxyBeanMethods = false)
public class HealthConfig {

    @Bean
    HealthIndicator indexHealthIndicator(LuceneIndexService index, IngestionService ingestion) {
        return () -> {
            LuceneIndexService.Stats stats = index.stats();
            IngestionService.Status status = ingestion.status();
            String state = stats.ready() ? (status.running() ? "REFRESHING" : "READY")
                    : (status.running() ? "INDEXING" : "EMPTY");
            Health.Builder health = stats.ready() || status.running() ? Health.up() : Health.down();
            health.withDetail("state", state)
                .withDetail("documents", stats.pages())
                .withDetail("chunks", stats.chunks());
            if (stats.indexedAt() != null) {
                health.withDetail("indexedAt", stats.indexedAt().toString());
            }
            if (status.lastError() != null) {
                health.withDetail("lastIngestionError", status.lastError());
            }
            return health.build();
        };
    }

    @Bean
    HealthIndicator modelHealthIndicator(@Value("${spring.ai.google.genai.chat.model}") String model,
            @Value("${spring.ai.google.genai.api-key:}") String apiKey) {
        return () -> (apiKey.isBlank() ? Health.down() : Health.up())
            .withDetail("name", model)
            .withDetail("provider", "Google AI Studio (Gemini Developer API)")
            .withDetail("apiKeyConfigured", !apiKey.isBlank())
            .build();
    }
}
