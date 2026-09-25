package com.claudecodecoach.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import com.claudecodecoach.chat.ChatService;
import com.claudecodecoach.chat.DocsQueryAugmenter;
import com.claudecodecoach.chat.FollowUpQueryTransformer;
import com.claudecodecoach.chat.ModelErrorMapper;
import com.claudecodecoach.conversation.ConversationService;
import com.claudecodecoach.ingestion.DocsFetcher;
import com.claudecodecoach.ingestion.HttpDocsFetcher;
import com.claudecodecoach.ingestion.IngestionService;
import com.claudecodecoach.retrieval.LuceneDocumentRetriever;
import com.claudecodecoach.retrieval.LuceneIndexService;

/**
 * Wires Spring AI (ChatClient + RetrievalAugmentationAdvisor) to the Lucene retriever.
 *
 * <p>Spring AI auto-configures a prototype-scoped {@link ChatClient.Builder}, not a
 * {@link ChatClient}; each injection point gets its own builder.
 */
@Configuration(proxyBeanMethods = false)
public class AiConfig {

    @Bean(destroyMethod = "close")
    LuceneIndexService luceneIndexService(CoachProperties properties) {
        return new LuceneIndexService(properties.retrieval().indexDir());
    }

    @Bean
    LuceneDocumentRetriever luceneDocumentRetriever(LuceneIndexService index, CoachProperties properties) {
        return new LuceneDocumentRetriever(index, properties.retrieval());
    }

    @Bean
    DocsFetcher docsFetcher(CoachProperties properties) {
        return new HttpDocsFetcher(properties.ingestion().fetchTimeout());
    }

    @Bean
    IngestionService ingestionService(CoachProperties properties, DocsFetcher fetcher, LuceneIndexService index) {
        return new IngestionService(properties.ingestion(), fetcher, index);
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(LuceneDocumentRetriever retriever,
            CoachProperties properties, ChatClient.Builder rewriteClientBuilder) {
        RetrievalAugmentationAdvisor.Builder rag = RetrievalAugmentationAdvisor.builder()
            .documentRetriever(retriever)
            .queryAugmenter(new DocsQueryAugmenter());
        if (properties.chat().queryRewrite()) {
            // Same model (the only one allowed), kept short and deterministic for search queries.
            ChatClient.Builder rewriteClient = rewriteClientBuilder
                .defaultOptions(GoogleGenAiChatOptions.builder().temperature(0.0).maxOutputTokens(96));
            QueryTransformer compression = CompressionQueryTransformer.builder()
                .chatClientBuilder(rewriteClient)
                .build();
            rag.queryTransformers(new FollowUpQueryTransformer(compression));
        }
        return rag.build();
    }

    @Bean
    ModelErrorMapper modelErrorMapper() {
        return new ModelErrorMapper();
    }

    @Bean
    ChatService chatService(ChatClient chatClient, RetrievalAugmentationAdvisor ragAdvisor,
            ConversationService conversations, ModelErrorMapper errors, CoachProperties properties,
            @Value("classpath:prompts/system.st") Resource systemPrompt,
            @Value("${spring.ai.google.genai.chat.model}") String modelName) throws IOException {
        return new ChatService(chatClient, ragAdvisor, conversations, errors,
                systemPrompt.getContentAsString(StandardCharsets.UTF_8), properties, modelName);
    }
}
