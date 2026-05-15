-- Reshape conversation_message into combined exchange rows, matching the shape used by
-- mention_message: one row per question/answer pair, no role column. Aligns the two
-- persistence paths and simplifies prompt assembly.
--
-- `user_message` is nullable because the opening assistant line emitted by /iniciar-conversa
-- has no user counterpart — it is still recorded so the assistant has its own greeting in
-- the replay, but there is nothing to put in user_message for that first row.
--
-- This drops existing conversation_message data (chat sessions are short-lived enough that
-- preserving them is not worth a backfill).
DROP TABLE IF EXISTS conversation_message;

CREATE TABLE conversation_exchange (
    id              BIGSERIAL   PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    user_message    TEXT,
    assistant_reply TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_conversation_exchange_conv
    ON conversation_exchange (conversation_id, created_at);
