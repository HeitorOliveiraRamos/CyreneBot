package com.cyrene.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the pure matcher behind the name-anchored retrieval tier: which KB entity names a
 * free-form question is considered to literally mention.
 */
class GameKnowledgeToolsTest {

    private val names = listOf(
        "Acheron",
        "Along the Passing Shore",
        "Band of Sizzling Thunder",
        "Yu", // too short to judge — never matched
        null,
    )

    @Test
    fun `matchNames finds single and multi-word entity names, accent and case insensitive`() {
        assertEquals(listOf("Acheron"), GameKnowledgeTools.matchNames("quem é a ACHERON?", names))
        assertEquals(
            listOf("Along the Passing Shore"),
            GameKnowledgeTools.matchNames("qual o passivo do cone along the passing shore?", names),
        )
    }

    @Test
    fun `matchNames requires the whole name as whole words`() {
        // Substring of a longer word must not fire (the "cora/coração" contract).
        assertTrue(GameKnowledgeTools.matchNames("o acheronte da mitologia", names).isEmpty())
        // One token of a multi-word name is not the name.
        assertTrue(GameKnowledgeTools.matchNames("qual o melhor shore?", names).isEmpty())
    }

    @Test
    fun `matchNames skips short names, nulls and caps the result`() {
        assertTrue(GameKnowledgeTools.matchNames("yu é bom?", names).isEmpty())
        val many = (1..6).map { "Personagem Número $it" }
        val matched = GameKnowledgeTools.matchNames(
            "fala de " + many.joinToString(", ") { it.lowercase() },
            many,
        )
        assertEquals(4, matched.size)
    }
}
