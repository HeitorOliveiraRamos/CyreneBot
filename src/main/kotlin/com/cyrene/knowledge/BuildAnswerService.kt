package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacterService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Deterministic, LLM-free answers for build-shaped questions ("qual a build do phainon?",
 * "melhor time do blade?", "relíquia e ornamento da hyacine?").
 *
 * The build data arrives at ingestion as structured JSON ([NanokaIngestionSource.buildDoc]
 * flattens ordered id lists into labeled lines), so re-asking a small model to compose the
 * answer from that prose only ever ADDS entropy: random headings, relic effects remixed onto
 * the wrong set. This service closes the loop without a model: gazetteer match → fetch the
 * build doc by character_id → parse the labeled lines back → render a fixed template scoped
 * to the asked facet, with each item's real effect text joined by exact name
 * ([GameKnowledgeTools.effectDocs] — the join the voice path already relies on).
 *
 * Returns null whenever the question isn't fully answerable this way (no character named, no
 * facet cue, any named character without a build doc, DB error) — the caller falls through to
 * the retrieval+voice pipeline, so this path can only ever replace an answer, never lose one.
 */
@Component
class BuildAnswerService(
    private val jdbc: JdbcTemplate,
    private val characters: HsrCharacterService,
    private val tools: GameKnowledgeTools,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The answer BODY (no opener — the caller wraps it with a greeting line). */
    fun answer(query: String): String? {
        val labels = wantedLabels(query)
        if (labels.isEmpty()) return null
        // A question spanning both families ("qual a build e a ult do welt?") is a
        // composition job — neither fixed template fits, so the LLM path keeps it.
        if (KitAnswerService.rawKitAsk(query) != null) return null
        val chars = characters.findInText(query).take(MAX_CHARACTERS)
        if (chars.isEmpty()) return null
        val sections = chars.map { c ->
            val doc = buildDocFor(c.id) ?: return null
            renderBuild(doc, labels, effectsFor(doc, labels)) ?: return null
        }
        log.debug("Deterministic build answer for '{}' ({} chars, facets {})", query, chars.size, labels)
        return sections.joinToString("\n\n")
    }

    /** The character's build doc content, by game id. Fail-open: null falls through to retrieval. */
    private fun buildDocFor(characterId: String): String? = try {
        jdbc.queryForList(
            "SELECT content FROM vector_store " +
                "WHERE metadata->>'category' = 'build' AND metadata->>'character_id' = ?",
            String::class.java,
            characterId,
        ).firstOrNull()
    } catch (e: Exception) {
        log.warn("build doc fetch failed for character {}: {}", characterId, e.message)
        null
    }

    /**
     * Effect text (the "Bônus …"/"Efeito …" lines of each item's own relic_set/light_cone
     * doc) for the items on the asked-facet lines only, keyed by exact item name. Feeding
     * [GameKnowledgeTools.effectDocs] just the facet lines scopes the join for free, same
     * as the retrieval path's prune-then-join order.
     */
    private fun effectsFor(content: String, labels: Set<String>): Map<String, String> {
        val itemLines = content.lines().filter { line ->
            labels.any(line::startsWith) && GameKnowledgeTools.ITEM_LINES.any(line::startsWith)
        }
        if (itemLines.isEmpty()) return emptyMap()
        val rows = tools.effectDocs(
            listOf(mapOf<String, Any?>("category" to "build", "content" to itemLines.joinToString("\n"))),
        )
        return rows.mapNotNull { row ->
            val name = row["name"] as? String ?: return@mapNotNull null
            val effect = effectLines(row["content"] as? String ?: "")
            if (effect.isEmpty()) null else name to effect.joinToString("\n")
        }.toMap()
    }

    internal companion object {
        /** Same listy-question ceiling as the KB's name-anchored tier. */
        private const val MAX_CHARACTERS = 4

        /** Words that ask for the whole build — every labeled line the doc has. */
        private val FULL_BUILD_WORDS = setOf("build", "builds")

        /** Effect lines inside a relic_set/light_cone doc (labels from both ingestion sources). */
        private val EFFECT_PREFIXES = listOf("Bônus", "Efeito")

        /**
         * Build-doc line labels the [query] asks for: the facet vocabulary is
         * [GameKnowledgeTools.LINE_CUES] verbatim (one cue map, no drift with pruning),
         * plus "build(s)" meaning all of them. Empty = not a build-shaped question. Pure.
         */
        internal fun wantedLabels(query: String): Set<String> {
            val tokens = HsrCharacterService.normalize(query).split(' ')
            val labels = tokens.flatMap { GameKnowledgeTools.LINE_CUES[it].orEmpty() }.toMutableSet()
            if (tokens.any { it in FULL_BUILD_WORDS }) labels += GameKnowledgeTools.BUILD_LINE_LABELS
            return labels
        }

        /**
         * Fixed-template render of one build doc scoped to [labels]: title line first, then
         * each asked line as a bold-headed block in the doc's own (stable) order. Item lines
         * become numbered best-first lists with each item's effect text underneath; stat and
         * team lines render their payload as-is. Null when no asked line exists in this doc
         * (e.g. a team question against a doc with no team line) — the caller falls through
         * rather than sending an empty shell. Pure.
         */
        internal fun renderBuild(content: String, labels: Set<String>, effects: Map<String, String>): String? {
            val lines = content.lines().map(String::trim).filter(String::isNotEmpty)
            if (lines.isEmpty()) return null
            val title = lines.first().substringBefore(" (Honkai").trimEnd('.', ' ')
            val blocks = lines.drop(1)
                .filter { line -> labels.any(line::startsWith) }
                .map { line -> renderLine(line, effects) }
            if (blocks.isEmpty()) return null
            return (listOf("**$title**") + blocks).joinToString("\n\n")
        }

        private fun renderLine(line: String, effects: Map<String, String>): String {
            val header = line.substringBefore(':').trim()
            val payload = line.substringAfter(':').trim()
            if (GameKnowledgeTools.ITEM_LINES.none(line::startsWith)) return "**$header**\n$payload"
            val items = payload.split(';').map(String::trim).filter(String::isNotEmpty)
            return "**$header**\n" + items.mapIndexed { i, name ->
                val effect = effects[name]?.lines()?.joinToString("\n") { "· $it" }
                listOfNotNull("${i + 1}. **$name**", effect).joinToString("\n")
            }.joinToString("\n")
        }

        internal fun effectLines(docContent: String): List<String> =
            docContent.lines().map(String::trim).filter { l -> EFFECT_PREFIXES.any(l::startsWith) }
    }
}
