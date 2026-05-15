-- Reshape mention_message: store one row per question/answer exchange instead of two rows
-- per role. The @-mention reply path no longer threads prior context into the prompt, but
-- we still persist exchanges so a cron can re-summarize the user's personality every N
-- exchanges (see UserInfoService).
--
-- This drops existing data; the mention table held only short-lived rolling history that
-- is no longer used in the prompt anyway.
DROP TABLE IF EXISTS mention_message;

CREATE TABLE mention_message (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    guild_id        VARCHAR(64),
    channel_id      VARCHAR(64) NOT NULL,
    user_message    TEXT        NOT NULL,
    assistant_reply TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_mention_message_user_guild_created
    ON mention_message (user_id, guild_id, created_at DESC);

CREATE INDEX ix_mention_message_user_channel_created
    ON mention_message (user_id, channel_id, created_at DESC);
