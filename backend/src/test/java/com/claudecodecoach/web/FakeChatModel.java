package com.claudecodecoach.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import reactor.core.publisher.Flux;

/**
 * Stands in for Gemma. Streams a scripted answer (each chunk's parts, with the same {@code isThought}
 * metadata Spring AI's Google GenAI model sets), or fails with a scripted error. Records every prompt.
 */
public class FakeChatModel implements ChatModel {

    /** One streamed chunk: its parts as (text, isThought) pairs. */
    public record Part(String text, boolean thought) {
    }

    public final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private volatile Supplier<Flux<ChatResponse>> script = () -> Flux.empty();

    public void streams(List<List<Part>> chunks) {
        this.script = () -> Flux.fromIterable(chunks).map(FakeChatModel::chunk);
    }

    public void fails(RuntimeException error) {
        this.script = () -> Flux.error(error);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return chunk(List.of(new Part("standalone query", false)));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        return script.get();
    }

    @Override
    public ChatOptions getOptions() {
        return GoogleGenAiChatOptions.builder().model("gemma-4-26b-a4b-it").build();
    }

    private static ChatResponse chunk(List<Part> parts) {
        List<Generation> generations = new ArrayList<>();
        for (Part p : parts) {
            generations.add(new Generation(
                    AssistantMessage.builder().content(p.text()).properties(Map.of("isThought", p.thought())).build()));
        }
        return new ChatResponse(generations);
    }
}
