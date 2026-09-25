package com.claudecodecoach.conversation;

import java.time.Instant;
import java.util.List;

/** Read models returned by the conversation API. */
public final class ConversationViews {

    private ConversationViews() {
    }

    public record Summary(String id, String title, Instant createdAt, Instant updatedAt) {
    }

    /**
     * @param index    position in the conversation; used to address feedback
     * @param role     "user" or "assistant"
     * @param feedback "UP", "DOWN" or null
     */
    public record MessageView(int index, String role, String content, List<SourceRef> sources, String feedback) {
    }

    public record Detail(String id, String title, Instant createdAt, Instant updatedAt, List<MessageView> messages) {
    }
}
