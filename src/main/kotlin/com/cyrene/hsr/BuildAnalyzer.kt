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
        )
    }

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

    /** Renders the report as Discord markdown, PT-BR, all numbers straight from the math above. */
    fun render(character: MihomoCharacter, report: BuildReport): String = buildString {
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
        report.weakest?.let {
            appendLine()
            appendLine(
                "Peça mais fraca: **${SLOT_NAMES[it.slot]}** (${fmt(it.score)}) — é aqui que um farm rende mais.",
            )
        }
        append("_Nota pela régua da comunidade (StarRailScore)._")
    }.trim()

    private fun fmt(score: Double): String = String.format("%.1f", score)
}
