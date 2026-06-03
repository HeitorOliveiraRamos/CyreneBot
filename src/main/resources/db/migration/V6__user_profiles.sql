-- Replaces the per-(user, guild) users_info cache with a single global per-user memory.
--
-- Rationale: name, role and permissions vary per guild but are cheap to read live from JDA,
-- so they no longer need to be persisted (and were duplicated across guilds). What IS worth
-- keeping is the bot's relationship with the person, which is the same regardless of server.
--
-- Privacy: memory is now OPT-IN. memory_enabled defaults FALSE; a row is only written once
-- the user opts in via /memoria. No existing data is migrated — the old cache is dropped.
DROP TABLE IF EXISTS users_info;

-- One row per Discord user. personality_summary is the soft, periodically re-summarized
-- profile; discrete remembered facts live in user_facts.
CREATE TABLE user_profiles (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             VARCHAR(64)  NOT NULL,
    memory_enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    personality_summary TEXT,
    exchanges_count     INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_user_profiles_user
    ON user_profiles (user_id);

-- Durable, verbatim things the bot was asked to remember about a user. Saved by the LLM via
-- the rememberAboutUser tool and recalled into the prompt. Only created while the user has
-- memory enabled; wiped when they opt out.
CREATE TABLE user_facts (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_user_facts_user
    ON user_facts (user_id, created_at DESC);
