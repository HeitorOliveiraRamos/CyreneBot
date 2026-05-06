CREATE TABLE conversation (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    channel_id  VARCHAR(64) NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at    TIMESTAMPTZ
);

-- At most one active conversation per (user, channel).
CREATE UNIQUE INDEX ux_conversation_active_user_channel
    ON conversation (user_id, channel_id)
    WHERE active;

CREATE INDEX ix_conversation_user_channel
    ON conversation (user_id, channel_id);

CREATE TABLE conversation_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,
    content         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_conversation_message_conv
    ON conversation_message (conversation_id, created_at);

CREATE TABLE moderation_warning (
    id         BIGSERIAL PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    guild_id   VARCHAR(64) NOT NULL,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_moderation_warning_user_guild
    ON moderation_warning (user_id, guild_id);
