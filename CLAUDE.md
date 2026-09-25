# claude-code-coach

Chatbot that answers Claude Code questions from the official docs. Spring Boot backend + React frontend.

## Commands

```bash
# backend (from backend/)
./mvnw spring-boot:run          # needs GEMINI_API_KEY (env var, root .env, or backend/.env)
./mvnw verify                   # all tests; LiveGemmaTest runs only if GEMINI_API_KEY is exported
./mvnw -Dtest=LuceneDocumentRetrieverTest test

# frontend (from frontend/)
npm run dev                     # http://localhost:5173, proxies /api to :8080 (or $BACKEND_PORT from root .env)
npm test                        # vitest
npm run lint && npm run build   # eslint (no `any`), tsc strict, vite build
npm run format                  # prettier

# everything, one command (asks for the key, picks a free port)
./start.sh                      # from the repo root

# whole stack in Docker
docker compose up --build       # http://localhost:8081
```

## Hard rules

- The only model is `gemma-4-26b-a4b-it`, named once in `application.yml`. Never add another chat,
  embedding, reranker or fallback model, and never write another model's name anywhere in the repo
  (not even in docs or tests): the acceptance check greps for it.
- `GEMINI_API_KEY` stays in the backend: read via `${GEMINI_API_KEY}` in application.yml, never
  `System.getenv()` (it can't see `.env` files), never logged, never returned by an endpoint.
- No raw HTML rendering in the frontend (no `rehype-raw`, no `dangerouslySetInnerHTML`).
- No TypeScript `any`.

## Spring AI 2.0.1 facts (verified against source at tag v2.0.1)

- Boot 4.1.1 is the Boot line Spring AI 2.0.1 is built on. Jackson 3 (`tools.jackson.*`).
- Model options sit directly under `spring.ai.google.genai.chat.*` (`.chat.model`, `.chat.temperature`).
  `.chat.options.*` is the deprecated 1.x form.
- Spring AI auto-configures a prototype `ChatClient.Builder`, not a `ChatClient`.
- `ChatClientRequestSpec.options(...)` takes a `ChatOptions.Builder` (e.g. `GoogleGenAiChatOptions.builder()`).
- Each Gemini response part becomes its own `Generation` with metadata `isThought`; read
  `chatResponse().getResults()` and skip thoughts. `.content()` only reads the first generation.
- `ContextualQueryAugmenter`'s empty-context template can't see the query, hence `DocsQueryAugmenter`.
- Streaming isn't retried by Spring AI; errors arrive as the SDK's `ApiException` (with `code()`)
  wrapped in `RuntimeException`/`IllegalStateException`. `ModelErrorMapper` walks the cause chain.

## Architecture (backend `com.claudecodecoach`)

| Package | What lives there |
|---|---|
| `config` | `CoachProperties` (all `coach.*` settings), Spring AI wiring (`AiConfig`), CORS, request-id + rate-limit filters, health indicators |
| `ingestion` | llms.txt parser, HTTP fetcher (rejects HTML interstitials), heading-aware `MarkdownChunker`, `IngestionService` (startup/cron/admin) |
| `retrieval` | `LuceneIndexService` (BM25, custom stop words, bigram proximity fields), `LuceneDocumentRetriever` (Spring AI `DocumentRetriever`) |
| `chat` | `ChatService` (one streamed turn), `DocsQueryAugmenter`, `FollowUpQueryTransformer`, `ModelErrorMapper` |
| `conversation` | Transcript in Spring AI's `SPRING_AI_CHAT_MEMORY` table via `ChatMemoryRepository`; titles and sources in `coach_*` tables (`schema.sql`) |
| `feedback` | Thumbs up/down per assistant message (`coach_feedback`) |
| `web` | REST + SSE controllers, `ApiExceptionHandler` |

Messages are addressed by `index` (position in the conversation). Regenerate drops the last
assistant message and the last user message, then re-asks.

Frontend: `src/hooks/useChat.ts` owns chat state; `src/lib/api.ts` + `src/lib/sse.ts` do the
streaming; `src/lib/theme.tsx` + the inline script in `index.html` handle light/dark/system without
a flash. Design tokens are CSS variables in `src/index.css` (juniper accent, ink code blocks).

## Retrieval tuning

Change `LuceneIndexService` stop words / boosts only with a probe over real questions: start the
backend once (it builds `backend/data/index`), then run queries against the index and check that
the starter questions' top hits are right (install → Quickstart/Advanced setup, MCP → MCP pages,
CLAUDE.md → memory page, plan mode → permission modes). `LuceneDocumentRetrieverTest` covers the
fixture corpus.
