-- Cache local dos personagens de HSR: nomes em 3 idiomas (StarRailRes, mesmos ids do mihomo)
-- e metadados de build do fribbels/hsr-optimizer (pesos de substats, main stats ideais e
-- conjuntos recomendados) em JSONB. Alimentada pela colheita agendada (HsrCharacterService);
-- data_exportado marca a última colheita — acima de 30 dias, recolhe em background.
CREATE TABLE hsr_character (
    id             VARCHAR(16) PRIMARY KEY,
    name_en        VARCHAR(120),
    name_pt        VARCHAR(120),
    name_es        VARCHAR(120),
    fribbels       JSONB,
    data_exportado TIMESTAMPTZ NOT NULL DEFAULT now()
);
