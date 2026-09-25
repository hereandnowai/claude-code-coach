package com.claudecodecoach.chat;

/**
 * A user-safe error sent as the SSE {@code error} event. Never carries upstream details or secrets.
 *
 * @param code              RATE_LIMITED, CONFIG_ERROR, UPSTREAM_ERROR, TIMEOUT or INTERNAL
 * @param message           friendly text for the UI
 * @param retryAfterSeconds for RATE_LIMITED, how long the client should wait; otherwise null
 */
public record ChatError(String code, String message, Integer retryAfterSeconds) {
}
