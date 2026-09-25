package com.claudecodecoach.chat;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genai.errors.ApiException;

/**
 * Maps failures from Google AI Studio (surfacing as the google-genai SDK's {@link ApiException},
 * usually wrapped by Spring AI and Reactor) to user-safe {@link ChatError}s, and logs the operator
 * detail once. The API key is never part of any message here.
 */
public class ModelErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(ModelErrorMapper.class);

    static final int DEFAULT_RETRY_SECONDS = 30;

    // Google's 429 text: "... Please retry in 37.24s." and/or a RetryInfo detail "retryDelay": "37s".
    private static final Pattern RETRY_IN = Pattern.compile("(?i)retry in\\s+([0-9]+(?:\\.[0-9]+)?)\\s*s");
    private static final Pattern RETRY_DELAY = Pattern.compile("(?i)retryDelay\"?\\s*[:=]\\s*\"?([0-9]+(?:\\.[0-9]+)?)s");

    public ChatError map(Throwable error) {
        ApiException api = find(error, ApiException.class);
        if (api != null) {
            return mapApi(api);
        }
        if (find(error, TimeoutException.class) != null || find(error, SocketTimeoutException.class) != null
                || find(error, HttpTimeoutException.class) != null) {
            log.warn("Model call timed out: {}", rootMessage(error));
            return new ChatError("TIMEOUT", "The model took too long to respond. Please try again.", null);
        }
        IllegalArgumentException badOption = find(error, IllegalArgumentException.class);
        if (badOption != null && String.valueOf(badOption.getMessage()).contains("hinking")) {
            log.error("Model option rejected: {}. Check coach.model.thinking-level.", badOption.getMessage());
            return configError();
        }
        log.error("Unexpected error while streaming an answer", error);
        return new ChatError("INTERNAL", "Something went wrong while generating the answer. Please try again.",
                null);
    }

    private ChatError mapApi(ApiException e) {
        int code = e.code();
        if (code == 429) {
            int wait = retryAfterSeconds(e.message());
            log.warn("Google AI Studio rate limit (HTTP 429, status {}); client told to retry in {}s", e.status(),
                    wait);
            return new ChatError("RATE_LIMITED",
                    "Gemma is receiving too many requests right now. Please try again in " + wait + " seconds.",
                    wait);
        }
        if (code == 400 || code == 401 || code == 403 || code == 404) {
            log.error("Google AI Studio rejected the request (HTTP {} {}): {}. Check GEMINI_API_KEY, that the key "
                    + "is an AI Studio key, and the model name in application.yml.", code, e.status(), e.message());
            return configError();
        }
        if (code == 408 || code == 504) {
            log.warn("Google AI Studio timeout (HTTP {}): {}", code, e.message());
            return new ChatError("TIMEOUT", "The model took too long to respond. Please try again.", null);
        }
        log.warn("Google AI Studio error (HTTP {} {}): {}", code, e.status(), e.message());
        return new ChatError("UPSTREAM_ERROR", "The model service had a problem. Please try again in a moment.",
                null);
    }

    private static ChatError configError() {
        return new ChatError("CONFIG_ERROR",
                "The assistant is not configured correctly, so it can't answer right now. Please tell the administrator.",
                null);
    }

    static int retryAfterSeconds(String message) {
        if (message != null) {
            for (Pattern p : new Pattern[] { RETRY_DELAY, RETRY_IN }) {
                Matcher m = p.matcher(message);
                if (m.find()) {
                    return Math.max(1, (int) Math.ceil(Double.parseDouble(m.group(1))));
                }
            }
        }
        return DEFAULT_RETRY_SECONDS;
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable t = error;
        int depth = 0;
        while (t != null && depth++ < 12) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            t = t.getCause();
        }
        return null;
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
