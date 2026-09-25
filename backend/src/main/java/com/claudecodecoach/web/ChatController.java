package com.claudecodecoach.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.claudecodecoach.chat.ChatError;
import com.claudecodecoach.chat.ChatService;
import com.claudecodecoach.chat.StreamSink;
import com.claudecodecoach.config.CoachProperties;
import com.claudecodecoach.conversation.SourceRef;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/chat/stream} — Server-Sent Events: {@code meta}, {@code token}*, {@code sources},
 * {@code done}, or {@code error}. Every event's data is one line of JSON.
 */
@RestController
public class ChatController {

    public record ChatRequest(String conversationId, String message, Boolean regenerate) {
    }

    record TokenEvent(String text) {
    }

    record SourcesEvent(List<SourceRef> sources) {
    }

    private final ChatService chatService;
    private final JsonMapper json;
    private final CoachProperties properties;

    public ChatController(ChatService chatService, JsonMapper json, CoachProperties properties) {
        this.chatService = chatService;
        this.json = json;
        this.properties = properties;
    }

    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        String message = validate(request);
        SseEmitter emitter = new SseEmitter(properties.chat().streamTimeout().toMillis());
        SseSink sink = new SseSink(emitter);
        ChatService.Handle handle = chatService.stream(request.conversationId(), message,
                Boolean.TRUE.equals(request.regenerate()), sink);
        emitter.onTimeout(() -> {
            handle.cancel();
            emitter.complete();
        });
        emitter.onError(e -> handle.cancel());
        emitter.onCompletion(handle::cancel);
        return emitter;
    }

    private String validate(ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new BadRequestException("Message must not be empty.");
        }
        int max = properties.chat().maxMessageLength();
        if (request.message().length() > max) {
            throw new BadRequestException("Message is too long (max " + max + " characters).");
        }
        String id = request.conversationId();
        if (id != null && !id.isBlank()) {
            try {
                UUID.fromString(id);
            }
            catch (IllegalArgumentException e) {
                throw new BadRequestException("conversationId is not valid.");
            }
        }
        return request.message().strip();
    }

    private final class SseSink implements StreamSink {

        private final SseEmitter emitter;

        SseSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void meta(Meta meta) {
            send("meta", meta);
        }

        @Override
        public void token(String text) {
            send("token", new TokenEvent(text));
        }

        @Override
        public void sources(List<SourceRef> sources) {
            send("sources", new SourcesEvent(sources));
        }

        @Override
        public void done(Done done) {
            send("done", done);
            emitter.complete();
        }

        @Override
        public void error(ChatError error) {
            send("error", error);
            emitter.complete();
        }

        private void send(String name, Object payload) {
            try {
                emitter.send(SseEmitter.event().name(name).data(json.writeValueAsString(payload)));
            }
            catch (IOException | IllegalStateException e) {
                throw new ClientGoneException(e);
            }
        }
    }
}
