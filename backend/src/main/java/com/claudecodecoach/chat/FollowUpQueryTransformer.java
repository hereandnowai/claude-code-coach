package com.claudecodecoach.chat;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * Turns a follow-up question ("and for Gradle?") into a standalone search query using recent chat
 * history. Only the search query changes; the model still answers the user's original wording.
 *
 * <p>Skipped on the first turn (nothing to resolve). Any failure, including a 429 from the model,
 * falls back to the original question so retrieval never blocks the answer.
 */
public class FollowUpQueryTransformer implements QueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(FollowUpQueryTransformer.class);

    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final int MAX_MESSAGE_CHARS = 600;
    private static final int MAX_QUERY_CHARS = 300;

    private final QueryTransformer delegate;

    public FollowUpQueryTransformer(QueryTransformer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Query transform(Query query) {
        List<Message> prior = priorTurns(query);
        if (prior.isEmpty()) {
            return query;
        }
        long started = System.nanoTime();
        try {
            Query rewritten = delegate.transform(query.mutate().history(prior).build());
            String text = sanitize(rewritten.text());
            if (text.isEmpty()) {
                return query;
            }
            log.info("Follow-up rewritten for retrieval in {} ms", (System.nanoTime() - started) / 1_000_000);
            log.debug("Rewritten query: '{}'", text);
            return query.mutate().text(text).build();
        }
        catch (RuntimeException e) {
            log.warn("Query rewrite failed ({}); using the original question", e.getMessage());
            return query;
        }
    }

    /** User/assistant turns before the current question, most recent last, trimmed for cost. */
    private static List<Message> priorTurns(Query query) {
        List<Message> turns = new ArrayList<>();
        for (Message m : query.history()) {
            if (m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT) {
                turns.add(m);
            }
        }
        // The history handed over by the RAG advisor ends with the current question itself.
        if (!turns.isEmpty() && turns.getLast().getMessageType() == MessageType.USER
                && query.text().equals(turns.getLast().getText())) {
            turns.removeLast();
        }
        if (turns.stream().noneMatch(m -> m.getMessageType() == MessageType.USER)) {
            return List.of();
        }
        List<Message> recent = turns.subList(Math.max(0, turns.size() - MAX_HISTORY_MESSAGES), turns.size());
        List<Message> trimmed = new ArrayList<>(recent.size());
        for (Message m : recent) {
            String text = m.getText() == null ? "" : m.getText();
            if (text.length() > MAX_MESSAGE_CHARS) {
                text = text.substring(0, MAX_MESSAGE_CHARS) + " ...";
            }
            trimmed.add(m.getMessageType() == MessageType.USER ? new UserMessage(text)
                    : AssistantMessage.builder().content(text).build());
        }
        return trimmed;
    }

    /** Keep the last non-empty line, strip quotes and labels, cap the length. */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String[] lines = raw.strip().split("\\R");
        String line = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                line = lines[i].strip();
                break;
            }
        }
        line = line.replaceFirst("(?i)^(standalone query|query)\\s*:\\s*", "").replaceAll("^[\"'`]+|[\"'`]+$", "");
        return line.length() > MAX_QUERY_CHARS ? line.substring(0, MAX_QUERY_CHARS) : line;
    }
}
