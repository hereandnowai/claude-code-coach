package com.claudecodecoach.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.claudecodecoach.Fixtures;
import com.claudecodecoach.retrieval.LuceneIndexService;
import com.claudecodecoach.web.FakeChatModel.Part;
import com.google.genai.errors.ClientException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full HTTP round trip of {@code POST /api/chat/stream} against the real Spring context, with the
 * model replaced by {@link FakeChatModel}: SSE event order and payloads, RAG grounding, thought
 * filtering, persistence, 429 mapping and input validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.datasource.url=jdbc:h2:mem:chat-stream-test;DB_CLOSE_DELAY=-1",
        "coach.ingestion.on-startup-if-missing=false",
        "coach.chat.query-rewrite=false",
        "coach.rate-limit.capacity=1000",
        "coach.model.thinking-level=NONE",
        "coach.retrieval.min-score=0.5" })
class ChatStreamControllerTest {

    private static final Path INDEX_DIR;

    static {
        try {
            INDEX_DIR = Files.createTempDirectory("coach-index");
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("coach.retrieval.index-dir", INDEX_DIR::toString);
    }

    @TestConfiguration
    static class FakeModelConfig {

        @Bean
        @Primary
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }
    }

    record Event(String name, JsonNode data) {
    }

    @Autowired
    FakeChatModel model;

    @Autowired
    ChatModel chatModel;

    @Autowired
    LuceneIndexService index;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        if (!index.isReady()) {
            index.rebuild(Fixtures.chunks(), Fixtures.PAGES.size());
        }
        model.prompts.clear();
    }

    @Test
    void streamsMetaTokensSourcesAndDone() throws Exception {
        assertThat(chatModel).isSameAs(model);
        model.streams(List.of(
                List.of(new Part("Planning: the user wants MCP. I will look at the docs.", true)),
                List.of(new Part("Run `claude mcp add --transport http github ", false)),
                List.of(new Part("https://api.githubcopilot.com/mcp/`.\n\n**Sources**\n- Connect Claude Code to tools via MCP",
                        false))));

        HttpResponse<String> response = post("{\"message\":\"How do I add an MCP server in Claude Code?\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("text/event-stream"));
        List<Event> events = parse(response.body());
        assertThat(events).extracting(Event::name).containsSubsequence("meta", "token", "sources", "done");
        assertThat(events.getFirst().name()).isEqualTo("meta");
        assertThat(events.getLast().name()).isEqualTo("done");

        String conversationId = events.getFirst().data().get("conversationId").asString();
        assertThat(events.getFirst().data().get("created").asBoolean()).isTrue();
        assertThat(events.getFirst().data().get("title").asString()).isEqualTo("How do I add an MCP server in Claude Code?");

        String answer = String.join("", events.stream().filter(e -> e.name().equals("token"))
            .map(e -> e.data().get("text").asString()).toList());
        assertThat(answer).startsWith("Run `claude mcp add").doesNotContain("Planning");

        JsonNode sources = events.stream().filter(e -> e.name().equals("sources")).findFirst().orElseThrow().data()
            .get("sources");
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("title").asString()).isEqualTo("Connect Claude Code to tools via MCP");
        assertThat(sources.get(0).get("url").asString()).isEqualTo(Fixtures.BASE + "mcp");

        assertThat(events.getLast().data().get("messageIndex").asInt()).isEqualTo(1);

        // The model saw the system prompt and the retrieved docs, fenced as reference data.
        List<Message> sent = model.prompts.getFirst().getInstructions();
        assertThat(sent.getFirst().getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(sent.getFirst().getText()).contains("claude-code-coach");
        assertThat(sent.getLast().getText()).contains("<documentation>", "claude mcp add",
                "User question: How do I add an MCP server in Claude Code?");

        // The exchange is persisted without the thought text.
        JsonNode detail = json.readTree(get("/api/conversations/" + conversationId).body());
        assertThat(detail.get("messages")).hasSize(2);
        assertThat(detail.get("messages").get(1).get("content").asString()).doesNotContain("Planning");
        assertThat(detail.get("messages").get(1).get("sources")).hasSize(1);
    }

    @Test
    void followUpTurnSendsHistoryAndRegenerateReplacesTheLastAnswer() throws Exception {
        model.streams(List.of(List.of(new Part("Add hooks under the hooks key in settings.json.", false))));
        String id = parse(post("{\"message\":\"How do hooks work?\"}").body()).getFirst().data().get("conversationId")
            .asString();

        model.streams(List.of(List.of(new Part("PreToolUse fires before a tool runs.", false))));
        post("{\"conversationId\":\"" + id + "\",\"message\":\"What is PreToolUse?\"}");
        List<Message> sent = model.prompts.getLast().getInstructions();
        assertThat(sent).extracting(Message::getMessageType)
            .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);

        model.streams(List.of(List.of(new Part("Regenerated answer.", false))));
        List<Event> events = parse(post("{\"conversationId\":\"" + id
                + "\",\"message\":\"What is PreToolUse?\",\"regenerate\":true}").body());
        assertThat(events.getLast().data().get("messageIndex").asInt()).isEqualTo(3);

        JsonNode messages = json.readTree(get("/api/conversations/" + id).body()).get("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(3).get("content").asString()).isEqualTo("Regenerated answer.");
    }

    @Test
    void offTopicQuestionGetsNoDocsAndNoSources() throws Exception {
        model.streams(List.of(List.of(new Part("I can only help with Claude Code.", false))));

        List<Event> events = parse(post("{\"message\":\"Give me a recipe for chocolate chip cookies\"}").body());

        String userTurn = model.prompts.getLast().getInstructions().getLast().getText();
        assertThat(userTurn).contains("No passage in the Claude Code documentation matched")
            .contains("Give me a recipe for chocolate chip cookies");
        JsonNode sources = events.stream().filter(e -> e.name().equals("sources")).findFirst().orElseThrow().data()
            .get("sources");
        assertThat(sources).isEmpty();
    }

    @Test
    void rateLimitFromGoogleBecomesAFriendlyErrorEventWithRetryDelay() throws Exception {
        model.fails(new RuntimeException("Failed to generate content", new ClientException(429, "RESOURCE_EXHAUSTED",
                "You exceeded your current quota. Please retry in 17.4s.")));

        List<Event> events = parse(post("{\"message\":\"How do I use plan mode?\"}").body());

        assertThat(events).extracting(Event::name).containsExactly("meta", "error");
        JsonNode error = events.getLast().data();
        assertThat(error.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(error.get("retryAfterSeconds").asInt()).isEqualTo(18);
        assertThat(error.get("message").asString()).contains("18 seconds").doesNotContain("quota");
    }

    @Test
    void invalidKeyBecomesAGenericConfigurationError() throws Exception {
        model.fails(new ClientException(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."));

        List<Event> events = parse(post("{\"message\":\"How do hooks work?\"}").body());

        JsonNode error = events.getLast().data();
        assertThat(error.get("code").asString()).isEqualTo("CONFIG_ERROR");
        assertThat(error.get("message").asString()).doesNotContain("API key");
    }

    @Test
    void rejectsEmptyAndTooLongMessages() throws Exception {
        assertThat(post("{\"message\":\"   \"}").statusCode()).isEqualTo(400);
        assertThat(post("{}").statusCode()).isEqualTo(400);
        HttpResponse<String> tooLong = post("{\"message\":\"" + "a".repeat(4001) + "\"}");
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(tooLong.body()).contains("max 4000");
        assertThat(post("{\"message\":\"" + "a".repeat(4000) + "\"}").statusCode()).isEqualTo(200);
        assertThat(post("{\"conversationId\":\"not-a-uuid\",\"message\":\"hi\"}").statusCode()).isEqualTo(400);
        assertThat(post("{\"conversationId\":\"" + java.util.UUID.randomUUID() + "\",\"message\":\"hi\"}").statusCode())
            .isEqualTo(404);
    }

    @Test
    void healthReportsModelAndIndexButNeverTheKey() throws Exception {
        HttpResponse<String> health = get("/api/health");
        JsonNode components = json.readTree(health.body()).get("components");
        assertThat(components.get("index").get("details").get("documents").asInt()).isEqualTo(4);
        assertThat(components.get("model").get("details").get("name").asString()).isEqualTo("gemma-4-26b-a4b-it");
        assertThat(health.body()).doesNotContainIgnoringCase("apiKey\":\"");
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri("/api/chat/stream"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    }

    private static final Pattern EVENT = Pattern.compile("event:(\\w+)\\ndata:(.*)");

    private List<Event> parse(String sse) {
        List<Event> events = new ArrayList<>();
        Matcher m = EVENT.matcher(sse.replace("\r\n", "\n"));
        while (m.find()) {
            events.add(new Event(m.group(1), json.readTree(m.group(2))));
        }
        return events;
    }
}
