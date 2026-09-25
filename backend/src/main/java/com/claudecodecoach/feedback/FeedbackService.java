package com.claudecodecoach.feedback;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.claudecodecoach.conversation.ConversationNotFoundException;
import com.claudecodecoach.conversation.ConversationService;

/** Thumbs up/down per assistant message. One rating per message; a new rating replaces the old. */
@Service
public class FeedbackService {

    public enum Rating {
        UP, DOWN
    }

    public record FeedbackRequest(String conversationId, int messageIndex, Rating rating, String comment) {
    }

    private final JdbcClient jdbc;
    private final ConversationService conversations;

    public FeedbackService(JdbcClient jdbc, ConversationService conversations) {
        this.jdbc = jdbc;
        this.conversations = conversations;
    }

    public void record(FeedbackRequest request) {
        if (!conversations.exists(request.conversationId())) {
            throw new ConversationNotFoundException(request.conversationId());
        }
        if (conversations.messageType(request.conversationId(), request.messageIndex()) != MessageType.ASSISTANT) {
            throw new IllegalArgumentException("messageIndex does not point at an assistant message");
        }
        String comment = request.comment() == null ? null : request.comment().strip();
        if (comment != null && comment.length() > 1000) {
            comment = comment.substring(0, 1000);
        }
        jdbc.sql("MERGE INTO coach_feedback (conversation_id, message_index, rating, comment, created_at) "
                + "KEY (conversation_id, message_index) VALUES (?, ?, ?, ?, ?)")
            .params(request.conversationId(), request.messageIndex(), request.rating().name(), comment,
                    Timestamp.from(Instant.now()))
            .update();
    }
}
