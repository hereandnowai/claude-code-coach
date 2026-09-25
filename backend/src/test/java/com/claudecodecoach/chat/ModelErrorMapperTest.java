package com.claudecodecoach.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;

class ModelErrorMapperTest {

    private final ModelErrorMapper mapper = new ModelErrorMapper();

    @Test
    void mapsWrapped429AndHonoursRetryDelay() {
        var error = new IllegalStateException("Stream processing failed", new RuntimeException("Failed to generate content",
                new ClientException(429, "RESOURCE_EXHAUSTED", "Quota exceeded. Please retry in 37.2s.")));

        ChatError mapped = mapper.map(error);

        assertThat(mapped.code()).isEqualTo("RATE_LIMITED");
        assertThat(mapped.retryAfterSeconds()).isEqualTo(38);
        assertThat(mapped.message()).contains("38 seconds");
    }

    @Test
    void readsRetryInfoDetailFormat() {
        assertThat(ModelErrorMapper.retryAfterSeconds("{\"retryDelay\": \"12s\"}")).isEqualTo(12);
    }

    @Test
    void defaultsTheRetryDelayWhenGoogleDoesNotSendOne() {
        ChatError mapped = mapper.map(new ClientException(429, "RESOURCE_EXHAUSTED", "Resource has been exhausted"));
        assertThat(mapped.retryAfterSeconds()).isEqualTo(ModelErrorMapper.DEFAULT_RETRY_SECONDS);
    }

    @Test
    void authAndRequestErrorsAreConfigErrorsWithoutDetails() {
        for (int code : new int[] { 400, 401, 403, 404 }) {
            ChatError mapped = mapper.map(new ClientException(code, "X", "API key not valid: AIza-secret-looking"));
            assertThat(mapped.code()).isEqualTo("CONFIG_ERROR");
            assertThat(mapped.message()).doesNotContain("AIza", "API key");
            assertThat(mapped.retryAfterSeconds()).isNull();
        }
    }

    @Test
    void serverErrorsAndTimeouts() {
        assertThat(mapper.map(new ServerException(503, "UNAVAILABLE", "overloaded")).code()).isEqualTo("UPSTREAM_ERROR");
        assertThat(mapper.map(new ServerException(504, "DEADLINE_EXCEEDED", "slow")).code()).isEqualTo("TIMEOUT");
        assertThat(mapper.map(new RuntimeException(new TimeoutException("idle"))).code()).isEqualTo("TIMEOUT");
        assertThat(mapper.map(new RuntimeException(new SocketTimeoutException("read"))).code()).isEqualTo("TIMEOUT");
        assertThat(mapper.map(new IllegalStateException("boom")).code()).isEqualTo("INTERNAL");
    }
}
