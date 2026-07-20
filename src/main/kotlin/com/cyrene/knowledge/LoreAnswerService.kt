package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacterService
import com.cyrene.hsr.HsrRepository
import com.cyrene.hsr.PersonagemHsr
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Lore questions — "me conta a história da himeko", "quem é a acheron?", "me da um resumo da lore
 * do welt". The source is the four narrative columns of `personagem_hsr` (`descricao`,
 * `detalhes_personagem`, `historia_personagem_parte1..4`), which no other path reads: the vector
 * store's profile doc only carries the one-line `descricao`.
 *
 * **Two outputs, because one shape doesn't fit both asks.** The full history runs to several
 * thousand characters of already well-written prose, so dumping it is right for "me conta a
 * história" and useless for "me da um resumo":
 *
 *  - [answer] renders it verbatim ([render]) for the full ask. Deterministic, like every other
 *    answer service — the source prose is better than anything a re-telling could produce, and
 *    handing multiple paragraphs to a small voice model is how details get recombined.
 *  - [context] instead returns the same text as GROUNDING for a summary-shaped ask, so the voice
 *    pass condenses it. Nothing is rendered directly, so the verifier and the answer cache apply
 *    to it exactly as they do to any other retrieved answer.
 *
 * Both are gated off the build and kit vocabularies, so a question that is really about a kit or a
 * build ("me fala sobre o kit da himeko") never lands here.
 */
@Component
class LoreAnswerService(
    private val repo: HsrRepository,
    private val characters: HsrCharacterService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Verbatim lore render for a full-history ask; null for summary asks and non-lore questions. */
    fun answer(query: String): String? {
        val ask = wantedLore(query) ?: return null
        if (ask.summarize) return null
        return lore(query)?.also { log.debug("Deterministic lore answer for '{}'", query) }
    }

    /** Lore text to hand the voice pass for a summary-shaped ask; null otherwise. */
    fun context(query: String): String? {
        val ask = wantedLore(query) ?: return null
        if (!ask.summarize) return null
        return lore(query)?.also { log.debug("Lore grounding (summarize) for '{}'", query) }
    }

    private fun lore(query: String): String? {
        val ids = characters.findInText(query).map { it.id }.take(MAX_CHARACTERS)
        if (ids.isEmpty()) return null
        val sections = repo.personagens(ids).mapNotNull { render(it, characters.displayName(it.characterId)) }
        return sections.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** [summarize] = the user asked for it short, so the voice pass condenses instead of a raw dump. */
    internal data class LoreAsk(val summarize: Boolean)

    internal companion object {
        private const val MAX_CHARACTERS = 2

        /** Words that ask for the narrative rather than the mechanics. */
        private val LORE_WORDS = setOf(
            "historia", "historias", "lore", "passado", "origem", "origens",
            "biografia", "background", "backstory", "sobre",
        )

        /** Asks for it SHORT — the arm that routes to the voice pass instead of a raw dump. */
        private val SUMMARY_WORDS = setOf(
            "resumo", "resuma", "resume", "resumida", "resumido", "resumidamente", "resumindo",
            "breve", "brevemente", "rapidinho", "rapido", "curto", "curta", "tldr",
        )

        /** "quem é X" — an identity question, which wants a short intro, not the full history. */
        private val WHO_IS = Regex("\\bquem\\s+(?:e|eh|sao|seria)\\b")

        /**
         * Whether the query is lore-shaped and, if so, whether it wants the short version.
         * Gated on the build/kit vocabularies for the same reason those two gate on each other:
         * a question naming a mechanic is that path's, and "sobre" is broad enough that without
         * the gate "me fala sobre a build da himeko" would land here. Pure.
         */
        internal fun wantedLore(query: String): LoreAsk? {
            if (BuildAnswerService.wantedLabels(query).isNotEmpty()) return null
            if (KitAnswerService.rawKitAsk(query) != null) return null
            val norm = HsrCharacterService.normalize(query)
            val tokens = norm.split(' ')
            val whoIs = WHO_IS.containsMatchIn(norm)
            if (tokens.none { it in LORE_WORDS } && !whoIs) return null
            // "quem é X" is inherently a give-me-the-gist question, so it summarizes even
            // without one of the summary words.
            return LoreAsk(summarize = whoIs || tokens.any { it in SUMMARY_WORDS })
        }

        /**
         * The narrative columns as bold-headed blocks, in reading order. Null when the row carries
         * no lore at all (some beta rows don't yet), so the caller falls through to retrieval.
         * Pure — [PersonagemHsr] is a plain model, so this is unit-testable without a DB.
         */
        internal fun render(p: PersonagemHsr, who: String): String? {
            val blocks = mutableListOf<String>()
            p.descricao?.takeIf { it.isNotBlank() }?.let { blocks += "**$who**\n$it" }
            p.detalhesPersonagem?.takeIf { it.isNotBlank() }?.let { blocks += "**Sobre $who**\n$it" }
            p.historias.forEachIndexed { i, h ->
                h?.takeIf { it.isNotBlank() }?.let { blocks += "**História — Parte ${i + 1}**\n$it" }
            }
            return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        }
    }
}
