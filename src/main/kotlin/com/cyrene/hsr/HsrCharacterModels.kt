package com.cyrene.hsr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * One row of the `hsr_character` cache table: the same numeric game id nanoka/mihomo use,
 * both names (PT + EN), and the full extracted kit and lore. Sourced PT-first from
 * starrailstation, falling back to nanoka's English for characters still in beta/leak that
 * srs hasn't published — so [namePt] and every kit/lore field is nullable (a source may not
 * carry a given piece). Trailblazer/March path-forms are distinct rows (distinct [id] +
 * [caminho]); fribbels build metadata now lives in its own table ([FribbelsMeta]).
 *
 * Field names stay `nameEn`/`namePt` (mapping the `nome_en`/`nome` columns) so the name-
 * matching callers don't churn; the new fields use the PT column names.
 */
data class HsrCharacter(
    val id: String,
    val nameEn: String? = null,
    val namePt: String? = null,
    val elemento: String? = null,
    val raridade: Int? = null,
    val caminho: String? = null,
    val faccao: String? = null,
    val descricao: String? = null,
    val atqBasico: String? = null,
    val pericia: String? = null,
    val periciaSuprema: String? = null,
    val talento: String? = null,
    val tecnica: String? = null,
    val tracoA2: String? = null,
    val tracoA4: String? = null,
    val tracoA6: String? = null,
    val eidolon1: String? = null,
    val eidolon2: String? = null,
    val eidolon3: String? = null,
    val eidolon4: String? = null,
    val eidolon5: String? = null,
    val eidolon6: String? = null,
    val detalhesPersonagem: String? = null,
    val historiaParte1: String? = null,
    val historiaParte2: String? = null,
    val historiaParte3: String? = null,
    val historiaParte4: String? = null,
) {
    /** All known names, for accent-insensitive matching across the languages users mix. */
    val names: List<String> get() = listOfNotNull(nameEn, namePt)
}

/**
 * Build metadata harvested from a fribbels/hsr-optimizer per-character config
 * (`src/lib/conditionals/character/<n>/<Name>.ts`):
 *
 *  - [subWeights]: substat → weight 0..1, from `scoring().stats` — fribbels' own ruler,
 *    used as the scoring fallback when StarRailScore lags a new character;
 *  - [mainStats]: slot (3–6) → ideal main stats, from `scoring().parts`;
 *  - [relicSets]/[ornamentSets]: recommended sets from `simulation()` (PT-BR names when
 *    StarRailRes could map them); empty for characters without sim metadata (supports);
 *  - [substatPriority]: ordered substat priority from `simulation().substats`.
 *
 * Stat keys everywhere are the game property keys ("CriticalChanceBase", "SpeedDelta"…)
 * that mihomo affixes and StarRailScore weights already use, so everything joins for free.
 */
data class FribbelsMeta(
    val subWeights: Map<String, Double>,
    val mainStats: Map<Int, List<String>>,
    val relicSets: List<List<String>>,
    val ornamentSets: List<String>,
    val substatPriority: List<String>,
) {

    fun toJson(mapper: ObjectMapper): String = mapper.writeValueAsString(
        mapOf(
            "subWeights" to subWeights,
            "mainStats" to mainStats.mapKeys { it.key.toString() },
            "relicSets" to relicSets,
            "ornamentSets" to ornamentSets,
            "substatPriority" to substatPriority,
        ),
    )

    companion object {
        /** JsonNode hand-parse (no jackson-kotlin in the project). Pure, fixture-testable. */
        fun fromJson(root: JsonNode): FribbelsMeta = FribbelsMeta(
            subWeights = buildMap {
                root.path("subWeights").fields().forEach { (k, v) -> put(k, v.asDouble(0.0)) }
            },
            mainStats = buildMap {
                root.path("mainStats").fields().forEach { (slot, stats) ->
                    slot.toIntOrNull()?.let { put(it, stats.map { s -> s.asText() }) }
                }
            },
            relicSets = root.path("relicSets").map { pair -> pair.map { it.asText() } },
            ornamentSets = root.path("ornamentSets").map { it.asText() },
            substatPriority = root.path("substatPriority").map { it.asText() },
        )
    }
}
