package com.cyrene.ai

import com.cyrene.ai.OllamaAiService.Companion.gazetteerFastPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The layer the answer-path tests skip. Every deterministic service can be correct and the bot
 * still answer from the model's own memory, because the INTENT GATE decides — before any of them
 * runs — whether the question reaches the knowledge pipeline at all.
 *
 * That's what happened on 2026-07-19: lore and roster questions were classified as chat, so the
 * conversational voice answered "me conta a história da himeko" from memory (invented lore) and
 * deflected "quantos membros tem na facção X" with "olha direto no jogo".
 */
class IntentGateRoutingTest {

    private val known = setOf("himeko", "acheron", "welt", "março", "hyacine")
    private fun mentions(s: String) = known.any { s.lowercase().contains(it) }

    /**
     * Stands in for RosterAnswerService.isTableQuestion. Mirrors its real contract, including the
     * rule that a server-ambiguous entity word ("membros") needs a REAL filter, not just a count.
     */
    private fun table(s: String): Boolean {
        val t = s.lowercase()
        val ambiguous = listOf("membros", "integrantes").any { it in t }
        val entity = ambiguous || listOf("personagens", "relíquias", "reliquias", "cones", "ornamentos").any { it in t }
        val filter = listOf("gelo", "vento", "facção", "faccao", "destruição", "estrelas").any { it in t }
        val count = listOf("quantos", "quantas", "total").any { it in t }
        if (!entity) return false
        val onlyAmbiguous = ambiguous && listOf("personagens", "relíquias", "reliquias", "cones", "ornamentos").none { it in t }
        return if (onlyAmbiguous) filter else filter || count
    }

    private fun route(msg: String) = gazetteerFastPath(msg, ::mentions, ::table)

    @Test
    fun `lore questions about a named character reach the knowledge pipeline`() {
        assertEquals(OllamaAiService.Intent.KNOWLEDGE, route("me conta a história da himeko"))
        assertEquals(OllamaAiService.Intent.KNOWLEDGE, route("qual a descrição da acheron?"))
        assertEquals(OllamaAiService.Intent.KNOWLEDGE, route("qual a facção do welt?"))
        assertEquals(OllamaAiService.Intent.KNOWLEDGE, route("o talento do memoespírito da hyacine"))
    }

    @Test
    fun `table questions reach it even though they name no character`() {
        assertEquals(OllamaAiService.Intent.KNOWLEDGE, route("quantos membros tem na facção Expresso Astral?"))
        assertEquals(OllamaAiService.Intent.KNOWLEDGE, route("quantas relíquias tem no total?"))
        assertEquals(OllamaAiService.Intent.KNOWLEDGE, route("me da 5 personagens de gelo aleatórios"))
    }

    @Test
    fun `storytelling that names nobody still defers to the LLM gate`() {
        // The pairing rule is what makes the new lore cues safe: these must NOT hard-route.
        assertNull(route("me conta uma história aí"))
        assertNull(route("qual a origem desse meme?"))
        assertNull(route("me fala sobre o seu passado"))
    }

    @Test
    fun `banter naming a character still defers to the LLM gate`() {
        // Regression guard for the 2026-07-17 fun-vs-facts rework.
        assertNull(route("será que a acheron me ama?"))
        assertNull(route("a himeko é linda demais"))
        assertNull(route("bom dia, alguém aí?"))
    }

    @Test
    fun `moderation verbs always win, even alongside game words`() {
        assertNull(route("bane o <@123> que ficou falando da build da acheron"))
        assertNull(route("expulsa quem tava falando de gelo nos personagens"))
        assertNull(route("limpa as mensagens sobre a build da himeko"))
    }

    @Test
    fun `server questions stay with moderation despite the shared noun`() {
        // "membros" is BOTH Discord vocabulary and faction vocabulary. A bare count can't tell
        // them apart, so only a real game filter claims the message for knowledge.
        assertNull(route("quantos membros tem no servidor?"))
        assertNull(route("quantos membros temos aqui?"))
        assertEquals(
            OllamaAiService.Intent.KNOWLEDGE,
            route("quantos membros tem na facção Expresso Astral?"),
        )
    }
}
