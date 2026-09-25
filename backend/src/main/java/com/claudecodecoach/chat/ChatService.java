package com.claudecodecoach.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import com.claudecodecoach.config.CoachProperties;
import com.claudecodecoach.conversation.ConversationService;
import com.claudecodecoach.conversation.ConversationService.Turn;
import com.claudecodecoach.conversation.SourceRef;
import com.claudecodecoach.retrieval.LuceneDocumentRetriever;

import reactor.core.Disposable;

/**
 * Runs one chat turn: persists the question, streams Gemma's answer through Spring AI (system
 * prompt + recent history + RAG advisor), filters out thought parts, then persists the answer with
 * its sources. Partial answers are kept when the user presses Stop.
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Metadata key Spring AI's Google GenAI model sets on each response part. */
    static final String IS_THOUGHT = "isThought";

    private static final int FALLBACK_SOURCE_COUNT = 3;

    /** A running stream; {@link #cancel()} is safe to call more than once. */
    public interface Handle {

        void cancel();
    }

    private final ChatClient chatClient;
    private final RetrievalAugmentationAdvisor ragAdvisor;
    private final ConversationService conversations;
    private final ModelErrorMapper errors;
    private final String systemPrompt;
    private final CoachProperties.Chat settings;
    private final GoogleGenAiThinkingLevel thinkingLevel;
    private final String modelName;

    public ChatService(ChatClient chatClient, RetrievalAugmentationAdvisor ragAdvisor,
            ConversationService conversations, ModelErrorMapper errors, String systemPrompt,
            CoachProperties properties, String modelName) {
        this.chatClient = chatClient;
        this.ragAdvisor = ragAdvisor;
        this.conversations = conversations;
        this.errors = errors;
        this.systemPrompt = systemPrompt;
        this.settings = properties.chat();
        this.thinkingLevel = parseThinkingLevel(properties.model().thinkingLevel());
        this.modelName = modelName;
    }

    public Handle stream(String conversationId, String message, boolean regenerate, StreamSink sink) {
        String requestId = MDC.get("requestId");
        Turn turn = conversations.beginTurn(conversationId, message, regenerate);
        sink.meta(new StreamSink.Meta(turn.conversationId(), turn.title(), turn.created(), turn.userIndex()));

        long started = System.nanoTime();
        StringBuilder answer = new StringBuilder();
        AtomicReference<List<Document>> retrieved = new AtomicReference<>(List.of());
        AtomicReference<Usage> usage = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<Disposable> subscription = new AtomicReference<>();

        Runnable onCancel = () -> {
            if (finished.compareAndSet(false, true)) {
                Disposable d = subscription.get();
                if (d != null) {
                    d.dispose();
                }
                String partial = answer.toString().strip();
                if (!partial.isEmpty()) {
                    conversations.completeTurn(turn.conversationId(), partial, List.of());
                }
                withRequestId(requestId, () -> log.info("Stream cancelled by client after {} ms ({} chars kept)",
                        elapsedMs(started), partial.length()));
            }
        };

        GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder();
        if (thinkingLevel != null) {
            options.thinkingLevel(thinkingLevel);
        }

        Disposable disposable = chatClient.prompt()
            .system(systemPrompt)
            .messages(window(turn.history()))
            .user(message)
            .options(options)
            .advisors(ragAdvisor)
            .stream()
            .chatClientResponse()
            .timeout(settings.idleTimeout())
            .subscribe(response -> withRequestId(requestId, () -> {
                if (finished.get()) {
                    return;
                }
                captureDocuments(response, retrieved);
                ChatResponse chatResponse = response.chatResponse();
                if (chatResponse == null) {
                    return;
                }
                if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                    usage.set(chatResponse.getMetadata().getUsage());
                }
                String delta = visibleText(chatResponse);
                if (!delta.isEmpty()) {
                    answer.append(delta);
                    try {
                        sink.token(delta);
                    }
                    catch (StreamSink.ClientGoneException gone) {
                        onCancel.run();
                    }
                }
            }), error -> withRequestId(requestId, () -> {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                ChatError mapped = errors.map(error);
                String partial = answer.toString().strip();
                if (!partial.isEmpty()) {
                    conversations.completeTurn(turn.conversationId(), partial, List.of());
                }
                log.info("Stream failed after {} ms with {}", elapsedMs(started), mapped.code());
                safely(() -> sink.error(mapped));
            }), () -> withRequestId(requestId, () -> {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                String text = answer.toString().strip();
                if (text.isEmpty()) {
                    text = "Sorry, I couldn't produce an answer this time. Please try again.";
                    safely(sink::token, text);
                }
                List<SourceRef> sources = selectSources(retrieved.get(), text);
                int index = conversations.completeTurn(turn.conversationId(), text, sources);
                logCompletion(started, retrieved.get(), sources, usage.get(), text.length());
                safely(() -> sink.sources(sources));
                safely(() -> sink.done(new StreamSink.Done(turn.conversationId(), index)));
            }));
        subscription.set(disposable);
        if (finished.get()) {
            disposable.dispose();
        }
        return onCancel::run;
    }

    /** The last {@code historyWindow} messages, starting on a user message so turns stay paired. */
    List<Message> window(List<Message> history) {
        int from = Math.max(0, history.size() - settings.historyWindow());
        while (from < history.size() && history.get(from).getMessageType() != MessageType.USER) {
            from++;
        }
        return new ArrayList<>(history.subList(from, history.size()));
    }

    /** Concatenates all non-thought text parts of a streamed chunk. */
    static String visibleText(ChatResponse response) {
        StringBuilder sb = new StringBuilder();
        for (Generation g : response.getResults()) {
            AssistantMessage out = g.getOutput();
            if (out == null || Boolean.TRUE.equals(out.getMetadata().get(IS_THOUGHT))) {
                continue;
            }
            String text = out.getText();
            if (text != null) {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void captureDocuments(ChatClientResponse response, AtomicReference<List<Document>> retrieved) {
        if (!retrieved.get().isEmpty()) {
            return;
        }
        Object docs = response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (docs instanceof List<?> list && !list.isEmpty()) {
            retrieved.set((List<Document>) list);
        }
    }

    /**
     * Pages whose title the answer cites; if the model cited none, the top few retrieved pages. No
     * sources when nothing was retrieved or the answer says the docs don't cover it.
     */
    static List<SourceRef> selectSources(List<Document> documents, String answer) {
        if (documents.isEmpty() || answer.toLowerCase(Locale.ROOT).contains("couldn't find this in the claude code docs")) {
            return List.of();
        }
        Map<String, SourceRef> unique = new LinkedHashMap<>();
        for (Document d : documents) {
            String url = String.valueOf(d.getMetadata().get(LuceneDocumentRetriever.META_URL));
            String title = String.valueOf(d.getMetadata().get(LuceneDocumentRetriever.META_TITLE));
            unique.putIfAbsent(url, new SourceRef(title, url));
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        List<SourceRef> cited = unique.values()
            .stream()
            .filter(s -> lower.contains(s.title().toLowerCase(Locale.ROOT)))
            .toList();
        if (!cited.isEmpty()) {
            return cited;
        }
        return unique.values().stream().limit(FALLBACK_SOURCE_COUNT).toList();
    }

    private void logCompletion(long started, List<Document> retrieved, List<SourceRef> sources, Usage usage,
            int chars) {
        String tokens = usage == null ? "n/a"
                : "prompt=" + usage.getPromptTokens() + " completion=" + usage.getCompletionTokens() + " total="
                        + usage.getTotalTokens();
        log.info("Answer streamed: model={} latencyMs={} chars={} retrievedChunks={} sources={} tokens[{}]",
                modelName, elapsedMs(started), chars, retrieved.size(),
                sources.stream().map(SourceRef::url).toList(), tokens);
    }

    private static GoogleGenAiThinkingLevel parseThinkingLevel(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NONE")) {
            return null;
        }
        return GoogleGenAiThinkingLevel.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static void withRequestId(String requestId, Runnable action) {
        String previous = MDC.get("requestId");
        if (requestId != null) {
            MDC.put("requestId", requestId);
        }
        try {
            action.run();
        }
        finally {
            if (previous != null) {
                MDC.put("requestId", previous);
            }
            else {
                MDC.remove("requestId");
            }
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        }
        catch (StreamSink.ClientGoneException ignored) {
            // The client left; the answer is already persisted.
        }
    }

    private static void safely(java.util.function.Consumer<String> action, String value) {
        safely(() -> action.accept(value));
    }
}
