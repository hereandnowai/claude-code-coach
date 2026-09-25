package com.claudecodecoach.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.claudecodecoach.conversation.ConversationNotFoundException;

/**
 * JSON error bodies. The Content-Type is preset so the error is still written when the request asked
 * for {@code text/event-stream} (validation fails before a chat stream starts).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<ApiError> json(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return json(HttpStatus.BAD_REQUEST, ApiError.of("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> illegalArgument(IllegalArgumentException e) {
        return json(HttpStatus.BAD_REQUEST, ApiError.of("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return json(HttpStatus.BAD_REQUEST, ApiError.of("BAD_REQUEST", "Request body is missing or not valid JSON."));
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    ResponseEntity<ApiError> notFound(ConversationNotFoundException e) {
        return json(HttpStatus.NOT_FOUND, ApiError.of("NOT_FOUND", "Conversation not found."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return json(HttpStatus.NOT_FOUND, ApiError.of("NOT_FOUND", "Not found."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of("INTERNAL", "Something went wrong. Please try again."));
    }
}
