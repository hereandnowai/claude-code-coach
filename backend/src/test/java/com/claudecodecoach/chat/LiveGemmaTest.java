package com.claudecodecoach.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Opt-in: calls the real gemma-4-26b-a4b-it through Spring AI's Google GenAI starter. Skipped unless
 * GEMINI_API_KEY is set in the environment. Uses one request against your AI Studio quota.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(properties = { "spring.datasource.url=jdbc:h2:mem:live-test;DB_CLOSE_DELAY=-1",
        "coach.ingestion.on-startup-if-missing=false", "coach.retrieval.index-dir=${java.io.tmpdir}/coach-live-index" })
class LiveGemmaTest {

    @Autowired
    ChatClient chatClient;

    @Test
    void gemmaAnswersThroughSpringAi() {
        ChatResponse response = chatClient.prompt()
            .system("Reply directly with the final answer only.")
            .user("Reply with exactly one word: pong")
            .call()
            .chatResponse();

        assertThat(response).isNotNull();
        assertThat(ChatService.visibleText(response)).containsIgnoringCase("pong");
        assertThat(response.getMetadata().getModel()).containsIgnoringCase("gemma");
    }
}
