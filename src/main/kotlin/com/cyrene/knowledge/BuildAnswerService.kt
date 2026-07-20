package com.cyrene.knowledge

import com.cyrene.hsr.BuildView
import com.cyrene.hsr.ConeDeLuz
import com.cyrene.hsr.HsrCharacterService
import com.cyrene.hsr.HsrRepository
import com.cyrene.hsr.ItemEffect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Deterministic, LLM-free answers for build-shaped questions ("qual a build do phainon?",
 * "melhor time do blade?", "relíquia e ornamento da hyacine?").
 *
 * Seam 2: the recommendation is read straight from the `builds` row ([HsrRepository.builds]),
 * item FKs already resolved to name + effect text — so composing the answer is selection +
 * formatting a fixed template scoped to the asked facet, never generation. Re-asking a small
 * model to compose it from prose only ever ADDS entropy (random headings, effects remixed onto
 * the wrong set); this closes the loop without a model.
 *
 * Returns null whenever the question isn't fully answerable this way (no character named, no
 * facet cue, any named character without a build, an asked facet the build lacks) — the caller
 * falls through to the retrieval+voice pipeline, so this path can only ever replace an answer.
 */
@Component
class BuildAnswerService(
    private val repo: HsrRepository,
    private val characters: HsrCharacterService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The answer BODY (no opener — the caller wraps it with a greeting line). */
    fun answer(query: String): String? {
        val labels = wantedLabels(query)
        if (labels.isEmpty()) return null
        // A question spanning both families ("qual a build e a ult do welt?") is a
        // composition job — neither fixed template fits, so the LLM path keeps it.
        if (KitAnswerService.rawKitAsk(query) != null) return null
        val ids = characters.findInText(query).map { it.id }.take(MAX_CHARACTERS)
        if (ids.isEmpty()) return null

        // "qual o efeito do cone do phainon?" asks for THE cone that belongs to him, not the three
        // he can use. Only fires on a cone-only, singular-possessive ask; anything comparative or
        // plural ("melhor cone pra…", "quais cones…") is the recommendation list below. Falls
        // through when any named character has no assigned cone, so this can only ever sharpen.
        if (labels == setOf("Cone de Luz") && wantsSignatureCone(query)) {
            val cones = ids.mapNotNull { id ->
                repo.coneAssinatura(id)?.let { renderSignatureCone(characters.displayName(id), it) }
            }
            if (cones.size == ids.size) {
                log.debug("Deterministic signature-cone answer for '{}'", query)
                return cones.joinToString("\n\n")
            }
        }

        val byId = repo.builds(ids).associateBy { it.characterId }
        // Every named character must have a build with the asked facet, or the LLM path keeps it.
        val sections = ids.map { id ->
            renderBuild(byId[id] ?: return null, labels, characters.displayName(id)) ?: return null
        }
        log.debug("Deterministic build answer for '{}' ({} chars, facets {})", query, ids.size, labels)
        return sections.joinToString("\n\n")
    }

    internal companion object {
        /** Same listy-question ceiling as the KB's name-anchored tier. */
        private const val MAX_CHARACTERS = 4

        /** Words that ask for the whole build — every facet the row has. */
        private val FULL_BUILD_WORDS = setOf("build", "builds")

        /** "cone do X" / "cone de luz da X" — the cone bound to a character by a possessive. */
        private val CONE_POSSESSIVE = Regex("\\bcone(?:\\s+de\\s+luz)?\\s+d[oae]\\b")

        /** Explicit ways of naming the signature cone. */
        private val CONE_SIGNATURE_WORDS = setOf("assinatura", "signature", "dedicado", "exclusivo")

        /** Comparative/plural vocabulary — these want the ranked recommendation, not one cone. */
        private val CONE_LIST_WORDS = setOf(
            "melhor", "melhores", "cones", "quais", "opcoes", "opcao",
            "alternativas", "recomendado", "recomendados", "recomendacao",
        )

        /**
         * True when the question asks for THE cone of a character rather than the ranked list.
         * The list vocabulary wins outright, so an ambiguous phrasing degrades to today's answer.
         * Pure.
         */
        internal fun wantsSignatureCone(query: String): Boolean {
            val norm = HsrCharacterService.normalize(query)
            val tokens = norm.split(' ')
            if (tokens.any { it in CONE_LIST_WORDS }) return false
            return CONE_POSSESSIVE.containsMatchIn(norm) ||
                (tokens.contains("cone") && tokens.any { it in CONE_SIGNATURE_WORDS })
        }

        /** `**{who} — Cone de Luz assinatura**` + the cone's own stat line and effect. Pure. */
        internal fun renderSignatureCone(who: String, cone: ConeDeLuz): String {
            val facts = listOfNotNull(
                cone.raridade?.let { "$it★" },
                cone.caminho,
            ).joinToString(" · ")
            val head = listOfNotNull(
                "**$who — Cone de Luz assinatura**",
                "**${cone.nome}**" + facts.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty(),
            )
            val effect = cone.efeitoDescricao?.takeIf { it.isNotBlank() }?.let { desc ->
                "Efeito" + (cone.efeitoNome?.let { " ($it)" } ?: "") + ": $desc"
            }
            return (head + listOfNotNull(effect)).joinToString("\n")
        }

        /** Facet label → the block header rendered for it (doc-order source: [GameKnowledgeTools.BUILD_LINE_LABELS]). */
        private val BLOCK_HEADERS = mapOf(
            "Relíquias" to "Relíquias (4 peças, melhor primeiro)",
            "Ornamento Planar" to "Ornamento Planar (melhor primeiro)",
            "Cone de Luz" to "Cone de Luz (melhor primeiro)",
            "Main stats" to "Main stats",
            "Substats" to "Substats",
            "Equipe recomendada" to "Equipe recomendada",
        )

        /**
         * Build-doc facet labels the [query] asks for: the facet vocabulary is
         * [GameKnowledgeTools.LINE_CUES] verbatim (one cue map, shared with the semantic
         * prune path), plus "build(s)" meaning all of them. Empty = not build-shaped. Pure.
         */
        internal fun wantedLabels(query: String): Set<String> {
            val tokens = HsrCharacterService.normalize(query).split(' ')
            val labels = tokens.flatMap { GameKnowledgeTools.LINE_CUES[it].orEmpty() }.toMutableSet()
            if (tokens.any { it in FULL_BUILD_WORDS }) labels += GameKnowledgeTools.BUILD_LINE_LABELS
            return labels
        }

        /**
         * Fixed-template render of one build scoped to [labels]: title line first, then each
         * asked facet as a bold-headed block in the canonical doc order. Item facets become
         * numbered best-first lists with each item's effect text underneath; stat/team facets
         * render their payload as-is. Null when no asked facet exists on this build (e.g. a team
         * question against a build with no team) — the caller falls through. Pure.
         */
        internal fun renderBuild(v: BuildView, labels: Set<String>, who: String = v.displayName): String? {
            val blocks = GameKnowledgeTools.BUILD_LINE_LABELS.filter { it in labels }.mapNotNull { renderBlock(v, it) }
            if (blocks.isEmpty()) return null
            return (listOf("**$who — build recomendada**") + blocks).joinToString("\n\n")
        }

        private fun renderBlock(v: BuildView, label: String): String? = when (label) {
            "Relíquias" -> itemBlock(BLOCK_HEADERS.getValue(label), v.reliquias)
            "Ornamento Planar" -> itemBlock(BLOCK_HEADERS.getValue(label), v.ornamentos)
            "Cone de Luz" -> itemBlock(BLOCK_HEADERS.getValue(label), v.cones)
            "Main stats" -> mainStats(v)?.let { "**Main stats**\n$it" }
            "Substats" -> v.substatusRecomendados?.let { "**Substats**\n$it" }
            "Equipe recomendada" -> v.equipeRecomendada?.let { "**Equipe recomendada**\n$it" }
            else -> null
        }

        /** Numbered best-first list of [items], each item's effect lines under it as "· "-bullets. */
        private fun itemBlock(header: String, items: List<ItemEffect>): String? {
            if (items.isEmpty()) return null
            val lines = items.mapIndexed { i, it ->
                val eff = it.efeitos.joinToString("\n") { e -> "· $e" }
                listOf("${i + 1}. **${it.nome}**", eff).filter(String::isNotEmpty).joinToString("\n")
            }
            return "**$header**\n" + lines.joinToString("\n")
        }

        private fun mainStats(v: BuildView): String? = listOfNotNull(
            v.mainStatCorpo?.let { "Corpo: $it" },
            v.mainStatPes?.let { "Pés: $it" },
            v.mainStatEsfera?.let { "Esfera Planar: $it" },
            v.mainStatCorda?.let { "Corda de Conexão: $it" },
        ).takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
