package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacterService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Deterministic, LLM-free answers for item-effect questions — "o que faz o cone Along the
 * Passing Shore?", "qual o bônus de 2 peças da Arena Rutilante?", "efeito do ornamento Izumo?".
 *
 * Third of the code-rendered family ([BuildAnswerService], [KitAnswerService]): each light-cone
 * and relic/ornament doc is already one self-contained PT-BR sheet (name, rarity/Path or set
 * kind, effect lines — [StarRailStationIngestionSource]), so when the query names a stored item
 * verbatim AND asks something effect-shaped, the doc is rendered as-is under a bold header.
 *
 * The category filter is the real guard, not the cue words: a character name matched by the
 * same gazetteer fetches no light_cone/relic_set doc and returns null, so this path can only
 * ever answer about actual items — character questions keep their build/kit/retrieval routes.
 * It also runs AFTER those two in [com.cyrene.ai.OllamaAiService], so a question that names a
 * character ("melhor cone pra acheron") is theirs before this service ever sees it.
 */
@Component
class ItemAnswerService(
    private val jdbc: JdbcTemplate,
    private val tools: GameKnowledgeTools,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun answer(query: String): String? {
        if (!wantsItem(query)) return null
        val names = GameKnowledgeTools.matchNames(query, tools.kbNames())
        if (names.isEmpty()) return null
        val docs = itemDocs(names)
        if (docs.isEmpty()) return null
        log.debug("Deterministic item answer for '{}' ({} docs)", query, docs.size)
        return docs.joinToString("\n\n") { rendered(it) }
    }

    /** light_cone / relic_set docs stored under [names]. Fail-open: empty falls through to retrieval. */
    private fun itemDocs(names: List<String>): List<String> = try {
        val placeholders = names.joinToString(",") { "?" }
        jdbc.queryForList(
            "SELECT content FROM vector_store " +
                "WHERE metadata->>'category' IN ('light_cone','relic_set') " +
                "AND metadata->>'name' IN ($placeholders)",
            String::class.java,
            *names.toTypedArray(),
        ).filterNotNull()
    } catch (e: Exception) {
        log.warn("item doc fetch failed for {}: {}", names, e.message)
        emptyList()
    }

    internal companion object {

        /**
         * Effect-ask vocabulary over normalized tokens. Comparison/judgment words ("melhor",
         * "vale") are deliberately absent — those want an opinion, which is the voice path's
         * job; this path only ever states what an item does.
         */
        private val ITEM_CUES = setOf(
            "efeito", "efeitos", "effect", "effects",
            "passiva", "passivas", "passivo", "passivos", "passive",
            "bonus", "peca", "pecas",
        )

        /**
         * True when the query asks what an item does. Bare "faz" is too broad ("o cone X faz
         * sentido?" is a judgment question), so it only cues as the "que faz" bigram —
         * same phrase-over-token trick as [KitAnswerService]'s "perícia suprema". Pure.
         */
        internal fun wantsItem(query: String): Boolean {
            val tokens = HsrCharacterService.normalize(query).split(' ')
            if (tokens.any { it in ITEM_CUES }) return true
            return tokens.zipWithNext().any { (a, b) -> a == "que" && b == "faz" }
        }

        /**
         * Bold title + body as stored, with the boilerplate " de Honkai: Star Rail." suffix
         * dropped from the title line. Pure.
         */
        internal fun rendered(doc: String): String {
            val lines = doc.trim().lines()
            val title = lines.first().trimEnd('.', ' ').removeSuffix(" de Honkai: Star Rail")
            val body = lines.drop(1).joinToString("\n").trim()
            return if (body.isEmpty()) "**$title**" else "**$title**\n$body"
        }
    }
}
