package com.claudecodecoach.web;

/** JSON error body for non-streaming failures. */
public record ApiError(String code, String message, Integer retryAfterSeconds) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
