-- Seam-2 cutover: the character gazetteer now reads `personagem_hsr` (the rich V17 table) and the
-- knowledge answers read the V17 tables directly, so the `hsr_character` cache has no remaining
-- reader. Its kit columns were already write-only; the names it carried are a subset of
-- `personagem_hsr` (character_id / nome / nome_en). `hsr_build_meta` stays — it is a separate table
-- backing /build's fribbels scoring, harvested on its own cycle.
DROP TABLE IF EXISTS hsr_character;
