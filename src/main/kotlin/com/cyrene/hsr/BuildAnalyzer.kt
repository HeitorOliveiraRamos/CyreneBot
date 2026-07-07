package com.cyrene.hsr

import kotlin.math.sqrt

/**
 * Deterministic relic/build scorer — no LLM anywhere. Implements the documented
 * StarRailScore formula so the numbers match what players see in community relic scorers:
 *
 *  - main affix: `(level+1)/16 × main_weight` (weight from the per-slot `main` table);
 *  - substats:   `Σ (count + step×0.1) × weight`, normalized by the character's `max`;
 *  - SRS-N = mean of the two; SRS-M = √SRS-N, shown as 0–10.
 *
 * Judgment beyond the number is also code: a main stat with zero weight for this character
 * is "wrong", substats with zero weight are dead rolls, the lowest-scoring piece is the
 * farming suggestion. The model never gets to invent any of this — `/build` renders this
 * report directly.
 */
object BuildAnalyzer {

    data class RelicScore(
        val slot: Int,
        val level: Int,
        val setName: String,
        val mainName: String,
        val mainWeight: Double,
        val score: Double,
        val rank: String,
        /** Substats this character has no use for (weight 0), by display name. */
        val deadSubs: List<String>,
    )

    data class BuildReport(
        val relics: List<RelicScore>,
        /** Slots 1–6 with no relic equipped. */
        val missingSlots: List<Int>,
        val totalScore: Double,
        val totalRank: String,
        val weakest: RelicScore?,
        /** Prioritized, ready-to-render farm suggestions; empty when the build needs none. */
        val farmPlan: List<String> = emptyList(),
    )

    fun analyze(character: MihomoCharacter, weights: ScoreWeights.CharWeights): BuildReport {
        val relics = character.relics.sortedBy { it.slot }.map { scoreRelic(it, weights) }
        val equippedSlots = relics.map { it.slot }.toSet()
        val missing = (1..6).filter { it !in equippedSlots }
        // Average over all 6 slots — an empty slot is a real deficiency, not a neutral one.
        val total = relics.sumOf { it.score } / 6.0
        return BuildReport(
            relics = relics,
            missingSlots = missing,
            totalScore = total,
            totalRank = rank(total),
            weakest = relics.minByOrNull { it.score },
            farmPlan = farmPlan(relics, missing, weights),
        )
    }

    /**
     * Concrete "what to farm next" list, most impactful first: empty slots, then pieces
     * whose fix is cheapest-per-gain (wrong main → replace; underleveled → just level it;
     * sub-par score → refarm chasing the right substats). At most [limit] lines; empty when
     * every piece is A-grade or better — nothing worth telling the player to grind.
     */
    internal fun farmPlan(
        relics: List<RelicScore>,
        missingSlots: List<Int>,
        weights: ScoreWeights.CharWeights,
        limit: Int = 3,
    ): List<String> {
        // Sort key = current score, so the worst deficiency leads; empty slots score -1.
        val plan = mutableListOf<Pair<Double, String>>()
        missingSlots.forEach { slot ->
            plan += -1.0 to "**${SLOT_NAMES[slot]}** vazio — equipar qualquer peça decente aqui é o maior ganho."
        }
        relics.forEach { r ->
            val slotName = SLOT_NAMES[r.slot] ?: "Peça ${r.slot}"
            when {
                // Slots 1-2 have fixed mains; only 3-6 can have a wrong one. Requires the
                // ruler to actually know this slot's ideal mains — no "troque por —" advice.
                r.slot >= 3 && r.mainWeight == 0.0 && weights.main[r.slot].orEmpty().any { it.value > 0 } ->
                    plan += r.score to
                        "**$slotName**: main atual (${r.mainName}) não serve — troque por ${idealMains(r.slot, weights)}."
                r.level < 15 ->
                    plan += r.score to "**$slotName**: subir de +${r.level} para +15 já melhora a nota."
                r.score < 7.0 ->
                    plan += r.score to
                        "**$slotName** (${fmt(r.score)}): refarm atrás de ${desiredSubs(weights)} nos substats."
            }
        }
        return plan.sortedBy { it.first }.take(limit).map { it.second }
    }

    /** Best main stats for [slot] under these weights, heaviest first, PT-BR labels. */
    private fun idealMains(slot: Int, weights: ScoreWeights.CharWeights): String =
        weights.main[slot].orEmpty().entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(3)
            .joinToString("/") { statPt(it.key) }
            .ifEmpty { "—" }

    /** The character's most-wanted substats under these weights, heaviest first. */
    private fun desiredSubs(weights: ScoreWeights.CharWeights): String =
        weights.weight.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(4)
            .joinToString(" > ") { statPt(it.key) }

    internal fun scoreRelic(relic: MihomoRelic, weights: ScoreWeights.CharWeights): RelicScore {
        val mainWeight = relic.mainAffix?.let { weights.main[relic.slot]?.get(it.type) } ?: 0.0
        val mainNorm = (relic.level + 1) / 16.0 * mainWeight

        val subsRaw = relic.subAffixes.sumOf { sub ->
            (sub.count + sub.step * 0.1) * (weights.weight[sub.type] ?: 0.0)
        }
        val subsNorm = if (weights.max > 0) subsRaw / weights.max else 0.0

        val srsN = (mainNorm + subsNorm) / 2.0
        val score = (sqrt(srsN) * 10.0).coerceIn(0.0, 10.0)

        return RelicScore(
            slot = relic.slot,
            level = relic.level,
            setName = relic.setName,
            mainName = relic.mainAffix?.name ?: "—",
            mainWeight = mainWeight,
            score = score,
            rank = rank(score),
            deadSubs = relic.subAffixes
                .filter { (weights.weight[it.type] ?: 0.0) == 0.0 }
                .map { it.name },
        )
    }

    /**
     * Score → letter. Our own thresholds (StarRailScore doesn't define ranks), chosen so a
     * fully-leveled piece with mostly-right stats lands S — the scale players expect.
     */
    internal fun rank(score: Double): String = when {
        score >= 8.5 -> "SS"
        score >= 8.0 -> "S"
        score >= 7.0 -> "A"
        score >= 6.0 -> "B"
        score >= 4.5 -> "C"
        else -> "D"
    }

    val SLOT_NAMES = mapOf(
        1 to "Cabeça", 2 to "Mãos", 3 to "Corpo",
        4 to "Pés", 5 to "Esfera Planar", 6 to "Corda de Conexão",
    )

    /**
     * Converts fribbels' harvested weights into the [ScoreWeights.CharWeights] shape so the
     * same formula scores characters StarRailScore doesn't know yet. Mains: recommended
     * stats weigh 1.0 (slots 1–2 fixed). Max: theoretical-best relic under these weights —
     * all 9 max-quality rolls into the top substats.
     */
    // ponytail: 1.2*(6*w1+w2+w3+w4) normalization lands within ~5% of StarRailScore's own
    // max (slightly strict); replicate their generator if the drift ever matters.
    internal fun fribbelsWeights(meta: FribbelsMeta): ScoreWeights.CharWeights? {
        val top = meta.subWeights.values.sortedDescending().take(4)
        val w1 = top.firstOrNull()?.takeIf { it > 0 } ?: return null
        return ScoreWeights.CharWeights(
            main = buildMap {
                put(1, mapOf("HPDelta" to 1.0))
                put(2, mapOf("AttackDelta" to 1.0))
                meta.mainStats.forEach { (slot, props) -> put(slot, props.associateWith { 1.0 }) }
            },
            weight = meta.subWeights,
            max = 1.2 * (6 * w1 + top.drop(1).sum()),
        )
    }

    /** Game property key → short PT-BR stat label for the recommendation lines. */
    private val PROP_PT = mapOf(
        "HPDelta" to "PV", "AttackDelta" to "ATQ", "DefenceDelta" to "DEF",
        "HPAddedRatio" to "PV%", "AttackAddedRatio" to "ATQ%", "DefenceAddedRatio" to "DEF%",
        "SpeedDelta" to "Velocidade", "CriticalChanceBase" to "Chance Crít.",
        "CriticalDamageBase" to "Dano Crít.", "StatusProbabilityBase" to "Acerto de Efeito",
        "StatusResistanceBase" to "RES de Efeito", "BreakDamageAddedRatioBase" to "Efeito de Quebra",
        "SPRatioBase" to "Regen. de Energia", "HealRatioBase" to "Aumento de Cura",
        "PhysicalAddedRatio" to "Dano Físico", "FireAddedRatio" to "Dano de Fogo",
        "IceAddedRatio" to "Dano de Gelo", "ThunderAddedRatio" to "Dano de Raio",
        "WindAddedRatio" to "Dano de Vento", "QuantumAddedRatio" to "Dano Quântico",
        "ImaginaryAddedRatio" to "Dano Imaginário",
    )

    /** Also used by the nanoka ingester to label recommended-build stats. */
    internal fun statPt(prop: String): String = PROP_PT[prop] ?: prop

    /**
     * The fribbels-sourced recommendation block: sets, ideal mains (✓ when the equipped
     * piece already matches) and substat priority. Purely presentational — nothing here
     * feeds the score.
     */
    internal fun renderRecommendations(character: MihomoCharacter, meta: FribbelsMeta): String = buildString {
        appendLine("**Recomendado (fribbels/hsr-optimizer):**")
        val equippedPieces = character.relicSets.associate { HsrCharacterService.normalize(it.name) to it.pieces }
        if (meta.relicSets.isNotEmpty()) {
            val options = meta.relicSets.map { pair ->
                val (a, b) = pair[0] to pair.getOrElse(1) { pair[0] }
                val label = if (a == b) "4pç $a" else "2pç $a + 2pç $b"
                val worn = if (a == b) (equippedPieces[HsrCharacterService.normalize(a)] ?: 0) >= 4
                else pair.all { (equippedPieces[HsrCharacterService.normalize(it)] ?: 0) >= 2 }
                if (worn) "$label ✓" else label
            }
            appendLine("• Conjuntos: ${options.joinToString(" | ")}")
        }
        if (meta.ornamentSets.isNotEmpty()) {
            val options = meta.ornamentSets.map { set ->
                if ((equippedPieces[HsrCharacterService.normalize(set)] ?: 0) >= 2) "$set ✓" else set
            }
            appendLine("• Ornamentos: ${options.joinToString(" | ")}")
        }
        val equippedMains = character.relics.associate { it.slot to it.mainAffix?.type }
        val mains = meta.mainStats.toSortedMap().mapNotNull { (slot, props) ->
            if (slot < 3 || props.isEmpty()) return@mapNotNull null
            val mark = when {
                equippedMains[slot] in props -> " ✓"
                equippedMains[slot] != null -> " ✗"
                else -> ""
            }
            "${SLOT_NAMES[slot]}: ${props.joinToString("/") { statPt(it) }}$mark"
        }
        if (mains.isNotEmpty()) appendLine("• Main stats: ${mains.joinToString(" • ")}")
        if (meta.substatPriority.isNotEmpty()) {
            appendLine("• Substats: ${meta.substatPriority.joinToString(" > ") { statPt(it) }}")
        }
    }.trim()

    /** Renders the report as Discord markdown, PT-BR, all numbers straight from the math above. */
    fun render(
        character: MihomoCharacter,
        report: BuildReport,
        meta: FribbelsMeta? = null,
        ruler: String = "StarRailScore",
    ): String = buildString {
        append("**${character.name}** — Nv. ${character.level} • E${character.eidolon}")
        if (character.elementName.isNotBlank()) append(" • ${character.elementName}")
        if (character.pathName.isNotBlank()) append(" • ${character.pathName}")
        appendLine()
        character.lightCone?.let {
            appendLine("Cone de Luz: ${it.name} (S${it.superimposition}, Nv. ${it.level})")
        }
        if (character.relicSets.isNotEmpty()) {
            appendLine("Conjuntos: " + character.relicSets.joinToString(" • ") { "${it.pieces}pç ${it.name}" })
        }
        appendLine()
        appendLine("**Nota da build: ${fmt(report.totalScore)}/10 (${report.totalRank})**")
        appendLine()
        report.relics.forEach { r ->
            append("• **${SLOT_NAMES[r.slot] ?: "Peça ${r.slot}"}** +${r.level} — ${fmt(r.score)}/10 (${r.rank})")
            // Slots 1-2 have fixed main stats, so only flag a wrong choice where one exists.
            if (r.slot >= 3 && r.mainWeight == 0.0) append(" · main fora do ideal (${r.mainName})")
            if (r.deadSubs.isNotEmpty()) append(" · substats sem uso: ${r.deadSubs.joinToString(", ")}")
            appendLine()
        }
        report.missingSlots.forEach { appendLine("• **${SLOT_NAMES[it]}** — vazio!") }
        if (report.farmPlan.isNotEmpty()) {
            appendLine()
            appendLine("**Próximo farm:**")
            report.farmPlan.forEach { appendLine("• $it") }
        } else {
            // Nothing worth grinding — still point at the weakest piece for the min-maxers.
            report.weakest?.let {
                appendLine()
                appendLine(
                    "Peça mais fraca: **${SLOT_NAMES[it.slot]}** (${fmt(it.score)}) — é aqui que um farm rende mais.",
                )
            }
        }
        meta?.let {
            appendLine()
            appendLine(renderRecommendations(character, it))
            appendLine()
        }
        append("_Nota pela régua da comunidade ($ruler)._")
    }.trim()

    private fun fmt(score: Double): String = String.format("%.1f", score)
}
