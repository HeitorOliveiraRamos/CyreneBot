-- Rich per-character cache: names (pt/en) + full kit text + lore, combined from
-- starrailstation (PT, released characters) and nanoka (EN, betas/leaks not yet on srs).
-- Replaces the old names+fribbels shape. Every text field is nullable: a source may not
-- carry a given piece (and betas have no PT at all). Repopulated wholesale by the scheduled
-- harvest (HsrCharacterService), which keeps the previous rows on a failed/implausible pull.
DROP TABLE IF EXISTS hsr_character;
CREATE TABLE hsr_character (
    id                          VARCHAR(16) PRIMARY KEY,
    nome                        VARCHAR(120),
    nome_en                     VARCHAR(120),
    elemento                    VARCHAR(40),
    raridade                    SMALLINT,
    caminho                     VARCHAR(40),
    faccao                      VARCHAR(160),
    descricao                   TEXT,
    atq_basico                  TEXT,
    pericia                     TEXT,
    pericia_suprema             TEXT,
    talento                     TEXT,
    tecnica                     TEXT,
    traco_a2                    TEXT,
    traco_a4                    TEXT,
    traco_a6                    TEXT,
    eidolon1                    TEXT,
    eidolon2                    TEXT,
    eidolon3                    TEXT,
    eidolon4                    TEXT,
    eidolon5                    TEXT,
    eidolon6                    TEXT,
    detalhes_personagem         TEXT,
    historia_personagem_parte1  TEXT,
    historia_personagem_parte2  TEXT,
    historia_personagem_parte3  TEXT,
    historia_personagem_parte4  TEXT,
    data_exportado              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Fribbels/hsr-optimizer build metadata (substat weights, ideal main stats, recommended
-- sets), split out of hsr_character. Backs /build's scoring fallback when StarRailScore
-- lags a freshly released unit. Harvested independently on the same ~30-day cycle.
DROP TABLE IF EXISTS hsr_build_meta;
CREATE TABLE hsr_build_meta (
    id             VARCHAR(16) PRIMARY KEY,
    fribbels       JSONB NOT NULL,
    data_exportado TIMESTAMPTZ NOT NULL DEFAULT now()
);
