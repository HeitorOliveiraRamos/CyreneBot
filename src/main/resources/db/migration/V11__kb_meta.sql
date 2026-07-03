-- Metadados da base de conhecimento (chave/valor). Primeira chave: 'hsr_versao_indexada',
-- gravada ao fim de cada reindex e comparada diariamente com a versão live do nanoka
-- (KbFreshnessCheck) para avisar quando a base ficou para trás de um patch.
CREATE TABLE kb_meta (
    chave         TEXT PRIMARY KEY,
    valor         TEXT NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
