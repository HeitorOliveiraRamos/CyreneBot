-- Answer cache for the knowledge pipeline: repeat HSR questions are served without any
-- LLM round-trip. Key = normalized question text (exact match — deliberately NOT semantic:
-- near-identical embeddings like "e1 da Acheron" vs "e2 da Acheron" must never collide).
-- LOCAL answers live until the next reindex truncates the table; WEB answers additionally
-- expire after 24h in the read query (leaks/banners move fast).
CREATE TABLE resposta_cache (
    pergunta_norm TEXT PRIMARY KEY,
    resposta      TEXT        NOT NULL,
    fonte         TEXT        NOT NULL,
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT now()
);
