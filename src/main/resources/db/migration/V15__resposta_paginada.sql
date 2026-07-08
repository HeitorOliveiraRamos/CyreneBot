-- Full text behind button-paginated replies (see MessagePaginator). Keyed by the short id
-- carried in the button custom id; mensagem_id is filled in after the Discord send so
-- reply-chain walks can recover the whole answer instead of just the visible page. Pages
-- are re-derived from texto on every click, so state survives restarts AND splitter tweaks.
-- Rows older than 30 days are swept opportunistically on each insert.
CREATE TABLE resposta_paginada (
    chave       TEXT PRIMARY KEY,
    mensagem_id TEXT,
    texto       TEXT        NOT NULL,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX resposta_paginada_mensagem_id_idx ON resposta_paginada (mensagem_id);
