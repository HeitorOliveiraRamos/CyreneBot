package com.cyrene.hsr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * One gazetteer entry: the shared numeric game id ([id], the `personagem_hsr.character_id`
 * nanoka/mihomo/`/build` all use) plus both display names ([namePt] from `nome`, [nameEn] from
 * `nome_en`). [namePt] is null for betas srs hasn't localized yet. The field names stay
 * `nameEn`/`namePt` so the name-matching callers don't churn. The rich kit/lore now lives in the
 * V17 tables ([PersonagemHsr]); fribbels build metadata in its own table ([FribbelsMeta]).
 *
 * [caminho]/[elemento] are carried because a NAME DOES NOT IDENTIFY A CHARACTER: `nome` is
 * ambiguous for 14 of the 97 rows (both 7 de Março forms, five Desbravador + five Desbravadora
 * paths), and `(nome, caminho)` is what's actually unique. They're stored raw — canonicalize
 * through [HsrTaxonomy] before comparing, since beta rows carry the English spellings.
 */
data class HsrCharacter(
    val id: String,
    val nameEn: String? = null,
    val namePt: String? = null,
    val caminho: String? = null,
    val elemento: String? = null,
    val raridade: Int? = null,
    val faccao: String? = null,
) {
    /** All known names, for accent-insensitive matching across the languages users mix. */
    val names: List<String> get() = listOfNotNull(nameEn, namePt)

    /** Preferred bare name (PT first), before any ambiguity suffix — see [HsrCharacterService.displayName]. */
    val baseName: String get() = namePt ?: nameEn ?: id
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
