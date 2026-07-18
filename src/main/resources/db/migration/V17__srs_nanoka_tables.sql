-- Rich SRS + nanoka extraction schema (tabelas_info.txt): the eventual replacement for the
-- hsr_character/hsr_build_meta cache. Unlike V16's joined "Nome\ndescrição" blobs, every ability/
-- eidolon/trace is split into a _nome + _descricao pair, and this adds relic/ornament pieces,
-- memosprite (Recordação) + euforia (Elation) abilities, and light cones with lore. Populated by
-- the flag-gated SrsNanokaPopulator (POPULATE_SRS_NANOKA=true); dormant otherwise. The current bot
-- still reads hsr_character — these tables coexist until the read paths are switched over.
--
-- character_id / id_reliquia / id_cone_de_luz natural keys are UNIQUE so the populate run is an
-- idempotent upsert (ON CONFLICT) and so generated serials can be read back for the signature join.
-- Drop children before parents.
DROP TABLE IF EXISTS builds CASCADE;
DROP TABLE IF EXISTS cones_de_luz CASCADE;
DROP TABLE IF EXISTS ornamentos_planos CASCADE;
DROP TABLE IF EXISTS reliquias CASCADE;
DROP TABLE IF EXISTS personagem_hsr CASCADE;

CREATE TABLE personagem_hsr (
    id_personagem_hsr               SERIAL PRIMARY KEY,
    character_id                    INTEGER UNIQUE,          -- shared game id (nanoka key == SRS rankKey)
    nome                            TEXT,                    -- PT (srs); null for betas srs hasn't published
    nome_en                         TEXT,
    elemento                        TEXT,
    caminho                         TEXT,
    raridade                        SMALLINT,
    faccao                          TEXT,
    descricao                       TEXT,
    atq_basico_nome                 TEXT,
    atq_basico_descricao            TEXT,
    pericia_nome                    TEXT,
    pericia_descricao               TEXT,
    pericia_suprema_nome            TEXT,
    pericia_suprema_descricao       TEXT,
    talento_nome                    TEXT,
    talento_descricao               TEXT,
    tecnica_nome                    TEXT,
    tecnica_descricao               TEXT,
    -- Only Recordação (Remembrance) characters have a memosprite.
    pericia_memoespirito_nome       TEXT,
    pericia_memoespirito_descricao  TEXT,
    talento_memoespirito_nome       TEXT,
    talento_memoespirito_descricao  TEXT,
    -- Only Euforia (Elation) characters have the euphoria skill.
    pericia_euforia_nome            TEXT,
    pericia_euforia_descricao       TEXT,
    traco_a2_nome                   TEXT,
    traco_a2_descricao              TEXT,
    traco_a4_nome                   TEXT,
    traco_a4_descricao              TEXT,
    traco_a6_nome                   TEXT,
    traco_a6_descricao              TEXT,
    eidolon1_nome                   TEXT,
    eidolon1_descricao              TEXT,
    eidolon2_nome                   TEXT,
    eidolon2_descricao              TEXT,
    eidolon3_nome                   TEXT,
    eidolon3_descricao              TEXT,
    eidolon4_nome                   TEXT,
    eidolon4_descricao              TEXT,
    eidolon5_nome                   TEXT,
    eidolon5_descricao              TEXT,
    eidolon6_nome                   TEXT,
    eidolon6_descricao              TEXT,
    detalhes_personagem             TEXT,
    historia_personagem_parte1      TEXT,
    historia_personagem_parte2      TEXT,
    historia_personagem_parte3      TEXT,
    historia_personagem_parte4      TEXT,
    data_exportado                  TIMESTAMP NOT NULL DEFAULT now()
);

-- Cavern relic sets (relicType 1 in srs): 2pc/4pc bonus + the four physical pieces with lore.
CREATE TABLE reliquias (
    id_reliquia        SERIAL PRIMARY KEY,
    nome               TEXT UNIQUE,
    efeito_2_pecas     TEXT,
    efeito_4_pecas     TEXT,
    cabeca_nome        TEXT,
    cabeca_descricao   TEXT,
    maos_nome          TEXT,
    maos_descricao     TEXT,
    corpo_nome         TEXT,
    corpo_descricao    TEXT,
    pes_nome           TEXT,
    pes_descricao      TEXT
);

-- Planar ornament sets (relicType 2 in srs): 2pc bonus + the Planar Sphere and Link Rope.
CREATE TABLE ornamentos_planos (
    id_ornamento_plano SERIAL PRIMARY KEY,
    nome               TEXT UNIQUE,
    efeito_2_pecas     TEXT,
    esfera_nome        TEXT,
    esfera_descricao   TEXT,
    corda_nome         TEXT,
    corda_descricao    TEXT
);

-- Light cones. id_personagem_hsr_atribuido = the character this cone is designed for (its
-- signature owner); left NULL at insert and set from each character's #1 recommended cone.
CREATE TABLE cones_de_luz (
    id_cone_de_luz               SERIAL PRIMARY KEY,
    nome                         TEXT UNIQUE,
    caminho                      TEXT,
    raridade                     SMALLINT,
    efeito_nome                  TEXT,
    efeito_descricao             TEXT,
    descricao                    TEXT,
    id_personagem_hsr_atribuido  INTEGER REFERENCES personagem_hsr(id_personagem_hsr)
);

-- Recommended build per character. Created now; populated in a later task.
CREATE TABLE builds (
    id_build               SERIAL PRIMARY KEY,
    id_personagem_hsr      INTEGER REFERENCES personagem_hsr(id_personagem_hsr),
    id_reliquia1           INTEGER REFERENCES reliquias(id_reliquia),
    id_reliquia2           INTEGER REFERENCES reliquias(id_reliquia),
    id_reliquia3           INTEGER REFERENCES reliquias(id_reliquia),
    id_ornamento_plano1    INTEGER REFERENCES ornamentos_planos(id_ornamento_plano),
    id_ornamento_plano2    INTEGER REFERENCES ornamentos_planos(id_ornamento_plano),
    id_ornamento_plano3    INTEGER REFERENCES ornamentos_planos(id_ornamento_plano),
    id_cone_de_luz1        INTEGER REFERENCES cones_de_luz(id_cone_de_luz),
    id_cone_de_luz2        INTEGER REFERENCES cones_de_luz(id_cone_de_luz),
    id_cone_de_luz3        INTEGER REFERENCES cones_de_luz(id_cone_de_luz),
    main_stat_corpo        TEXT,
    main_stat_pes          TEXT,
    main_stat_esfera       TEXT,
    main_stat_corda        TEXT,
    substatus_recomendados TEXT,
    equipe_recomendada     TEXT
);
