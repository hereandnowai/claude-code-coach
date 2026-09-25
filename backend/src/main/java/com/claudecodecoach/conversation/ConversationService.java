package com.claudecodecoach.conversation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.claudecodecoach.config.CoachProperties;
import com.claudecodecoach.conversation.ConversationViews.Detail;
import com.claudecodecoach.conversation.ConversationViews.MessageView;
import com.claudecodecoach.conversation.ConversationViews.Summary;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversation persistence. The full transcript is stored with Spring AI's
 * {@link ChatMemoryRepository} (JDBC/H2); titles, per-message source links and feedback live in the
 * app's own tables. Only a window of the transcript is sent to the model (see ChatService).
 */
@Service
public class ConversationService {

    private static final TypeReference<List<SourceRef>> SOURCES = new TypeReference<>() {
    };

    /** State needed to run one chat turn. {@code history} excludes the new user message. */
    public record Turn(String conversationId, String title, boolean created, List<Message> history, int userIndex) {
    }

    private final ChatMemoryRepository messages;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final int titleMaxLength;

    public ConversationService(ChatMemoryRepository messages, JdbcClient jdbc, JsonMapper json,
            CoachProperties properties) {
        this.messages = messages;
        this.jdbc = jdbc;
        this.json = json;
        this.titleMaxLength = properties.chat().titleMaxLength();
    }

    /**
     * Creates the conversation if needed, applies a regenerate (drops the last exchange) and stores
     * the new user message.
     */
    public Turn beginTurn(String conversationId, String userText, boolean regenerate) {
        boolean created = false;
        String id = conversationId;
        String title;
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            title = titleFrom(userText);
            Instant now = Instant.now();
            jdbc.sql("INSERT INTO coach_conversation (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)")
                .params(id, title, Timestamp.from(now), Timestamp.from(now))
                .update();
            created = true;
        }
        else {
            title = findTitle(id);
        }

        List<Message> transcript = new ArrayList<>(messages.findByConversationId(id));
        if (regenerate) {
            if (!transcript.isEmpty() && transcript.getLast().getMessageType() == MessageType.ASSISTANT) {
                deleteMessageExtras(id, transcript.size() - 1);
                transcript.removeLast();
            }
            if (!transcript.isEmpty() && transcript.getLast().getMessageType() == MessageType.USER) {
                transcript.removeLast();
            }
        }
        List<Message> history = List.copyOf(transcript);
        transcript.add(new UserMessage(userText));
        messages.saveAll(id, transcript);
        touch(id);
        return new Turn(id, title, created, history, transcript.size() - 1);
    }

    /** Appends the assistant answer and its sources; returns the answer's message index. */
    public int completeTurn(String id, String answer, List<SourceRef> sources) {
        List<Message> transcript = new ArrayList<>(messages.findByConversationId(id));
        transcript.add(AssistantMessage.builder().content(answer).build());
        messages.saveAll(id, transcript);
        int index = transcript.size() - 1;
        if (!sources.isEmpty()) {
            jdbc.sql("MERGE INTO coach_message_sources (conversation_id, message_index, sources_json) "
                    + "KEY (conversation_id, message_index) VALUES (?, ?, ?)")
                .params(id, index, json.writeValueAsString(sources))
                .update();
        }
        touch(id);
        return index;
    }

    public List<Summary> list() {
        return jdbc.sql("SELECT id, title, created_at, updated_at FROM coach_conversation ORDER BY updated_at DESC")
            .query((rs, n) -> new Summary(rs.getString("id"), rs.getString("title"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
            .list();
    }

    public Detail get(String id) {
        Summary s = jdbc.sql("SELECT id, title, created_at, updated_at FROM coach_conversation WHERE id = ?")
            .param(id)
            .query((rs, n) -> new Summary(rs.getString("id"), rs.getString("title"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
            .optional()
            .orElseThrow(() -> new ConversationNotFoundException(id));

        Map<Integer, List<SourceRef>> sources = new HashMap<>();
        jdbc.sql("SELECT message_index, sources_json FROM coach_message_sources WHERE conversation_id = ?")
            .param(id)
            .query(rs -> {
                sources.put(rs.getInt("message_index"), json.readValue(rs.getString("sources_json"), SOURCES));
            });
        Map<Integer, String> feedback = new HashMap<>();
        jdbc.sql("SELECT message_index, rating FROM coach_feedback WHERE conversation_id = ?")
            .param(id)
            .query(rs -> {
                feedback.put(rs.getInt("message_index"), rs.getString("rating"));
            });

        List<Message> transcript = messages.findByConversationId(id);
        List<MessageView> views = new ArrayList<>(transcript.size());
        for (int i = 0; i < transcript.size(); i++) {
            Message m = transcript.get(i);
            String role = m.getMessageType() == MessageType.USER ? "user" : "assistant";
            views.add(new MessageView(i, role, m.getText() == null ? "" : m.getText(),
                    sources.getOrDefault(i, List.of()), feedback.get(i)));
        }
        return new Detail(s.id(), s.title(), s.createdAt(), s.updatedAt(), views);
    }

    /** The role of the message at {@code index}, or null if there is none. */
    public MessageType messageType(String id, int index) {
        List<Message> transcript = messages.findByConversationId(id);
        return index >= 0 && index < transcript.size() ? transcript.get(index).getMessageType() : null;
    }

    public Summary rename(String id, String title) {
        String clean = title.strip();
        if (clean.length() > 200) {
            clean = clean.substring(0, 200);
        }
        int updated = jdbc.sql("UPDATE coach_conversation SET title = ? WHERE id = ?").params(clean, id).update();
        if (updated == 0) {
            throw new ConversationNotFoundException(id);
        }
        Detail d = get(id);
        return new Summary(d.id(), d.title(), d.createdAt(), d.updatedAt());
    }

    public void delete(String id) {
        int deleted = jdbc.sql("DELETE FROM coach_conversation WHERE id = ?").param(id).update();
        if (deleted == 0) {
            throw new ConversationNotFoundException(id);
        }
        messages.deleteByConversationId(id);
        jdbc.sql("DELETE FROM coach_message_sources WHERE conversation_id = ?").param(id).update();
        jdbc.sql("DELETE FROM coach_feedback WHERE conversation_id = ?").param(id).update();
    }

    public boolean exists(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM coach_conversation WHERE id = ?").param(id).query(Integer.class)
            .single() > 0;
    }

    private String findTitle(String id) {
        return jdbc.sql("SELECT title FROM coach_conversation WHERE id = ?")
            .param(id)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new ConversationNotFoundException(id));
    }

    private void touch(String id) {
        jdbc.sql("UPDATE coach_conversation SET updated_at = ? WHERE id = ?")
            .params(Timestamp.from(Instant.now()), id)
            .update();
    }

    private void deleteMessageExtras(String id, int index) {
        jdbc.sql("DELETE FROM coach_message_sources WHERE conversation_id = ? AND message_index = ?")
            .params(id, index)
            .update();
        jdbc.sql("DELETE FROM coach_feedback WHERE conversation_id = ? AND message_index = ?")
            .params(id, index)
            .update();
    }

    /** First line of the first message, cut on a word boundary. No model call. */
    String titleFrom(String text) {
        String line = text.strip().split("\\R", 2)[0].replaceAll("\\s+", " ");
        if (line.length() <= titleMaxLength) {
            return line;
        }
        int cut = line.lastIndexOf(' ', titleMaxLength - 1);
        return (cut > titleMaxLength / 2 ? line.substring(0, cut) : line.substring(0, titleMaxLength - 1)) + "…";
    }
}
