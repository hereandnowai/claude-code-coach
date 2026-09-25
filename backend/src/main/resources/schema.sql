-- App tables. Message text itself lives in Spring AI's SPRING_AI_CHAT_MEMORY table
-- (created by spring-ai-starter-model-chat-memory-repository-jdbc); these tables add what it lacks.

CREATE TABLE IF NOT EXISTS coach_conversation (
    id          VARCHAR(36)  PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

-- Source links shown under an assistant message; message_index is the message's position in the
-- conversation (same as SPRING_AI_CHAT_MEMORY.sequence_id).
CREATE TABLE IF NOT EXISTS coach_message_sources (
    conversation_id VARCHAR(36) NOT NULL,
    message_index   INT         NOT NULL,
    sources_json    CLOB        NOT NULL,
    PRIMARY KEY (conversation_id, message_index)
);

CREATE TABLE IF NOT EXISTS coach_feedback (
    conversation_id VARCHAR(36)  NOT NULL,
    message_index   INT          NOT NULL,
    rating          VARCHAR(4)   NOT NULL CHECK (rating IN ('UP', 'DOWN')),
    comment         VARCHAR(1000),
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (conversation_id, message_index)
);

CREATE INDEX IF NOT EXISTS coach_conversation_updated_idx ON coach_conversation (updated_at DESC);
