-- Rolling per-(user, channel) history for the @-mention reply path. Distinct from the
-- `conversation` table (which is reserved for explicit /iniciar-conversa sessions): a
-- mention exchange has no start/end ceremony, and the same user may have an active
-- session AND mention the bot in another channel simultaneously without conflict.
--
-- The MentionContextService trims rows older than a configurable window so this table
-- stays small even with chatty users.
CREATE TABLE mention_message (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    channel_id  VARCHAR(64) NOT NULL,
    role        VARCHAR(16) NOT NULL,
    content     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_mention_message_user_channel_created
    ON mention_message (user_id, channel_id, created_at DESC);
