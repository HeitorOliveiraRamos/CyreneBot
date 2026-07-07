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

    // -------------------- filterBySection -------------------- //

    private fun doc(category: String, content: String): Map<String, Any?> =
        mapOf("content" to content, "category" to category, "name" to "Acheron")

    private fun named(name: String, category: String, content: String): Map<String, Any?> =
        mapOf("content" to content, "category" to category, "name" to name)

    private val kit = listOf(
        doc("profile", "Acheron — personagem de Honkai: Star Rail."),
        doc("skill", "Acheron — habilidade: Octobolt Flash (Skill)"),
        doc("skill", "Acheron — habilidade: Slashed Dream Cries in Red (Ultimate)"),
        doc("trace", "Acheron — traço maior (Bonus Ability): The Abyss"),
        doc("eidolon", "Acheron — Eidolon 1: Red Oni"),
        doc("eidolon", "Acheron — Eidolon 2: Crimson Bloom"),
        doc("build", "Acheron — build recomendada (Honkai: Star Rail)."),
    )

    @Test
    fun `eidolon question with a number narrows to that single eidolon`() {
        val expected = listOf(kit[5])
        assertEquals(
            expected,
            GameKnowledgeTools.filterBySection(kit, "me retorna o efeito completo do eidolon 2 da acheron"),
        )
        assertEquals(expected, GameKnowledgeTools.filterBySection(kit, "o que faz a E2 da acheron?"))
    }

    @Test
    fun `eidolon question without a number keeps all eidolons`() {
        assertEquals(
            listOf(kit[4], kit[5]),
            GameKnowledgeTools.filterBySection(kit, "quais os eidolons da acheron?"),
        )
    }

    @Test
    fun `section cues filter by category, accent insensitive`() {
        assertEquals(
            listOf(kit[1], kit[2]),
            GameKnowledgeTools.filterBySection(kit, "qual a ultimate da acheron?"),
        )
        assertEquals(
            listOf(kit[3]),
            GameKnowledgeTools.filterBySection(kit, "quais os traços da acheron?"),
        )
    }

    @Test
    fun `relic, cone and team questions about a character pin the build doc`() {
        val build = listOf(kit[6])
        assertEquals(build, GameKnowledgeTools.filterBySection(kit, "qual a melhor relíquia pra acheron?"))
        assertEquals(build, GameKnowledgeTools.filterBySection(kit, "que cone usar na acheron?"))
        assertEquals(build, GameKnowledgeTools.filterBySection(kit, "que time montar com a acheron?"))
        // The same relic word against a relic-set entity keeps the relic_set doc.
        val relic = listOf(doc("relic_set", "Band of Sizzling Thunder — Conjunto de Relíquia"))
        assertEquals(
            relic,
            GameKnowledgeTools.filterBySection(relic, "efeito da relíquia band of sizzling thunder"),
        )
    }

    @Test
    fun `no cue, or a cue this entity has no docs for, leaves docs untouched`() {
        assertEquals(kit, GameKnowledgeTools.filterBySection(kit, "a acheron é boa?"))
        // A light cone has no eidolons — the cue must not turn a hit into a miss.
        val cone = listOf(doc("light_cone", "Along the Passing Shore — Cone de Luz"))
        assertEquals(
            cone,
            GameKnowledgeTools.filterBySection(cone, "eidolon 2 do cone along the passing shore"),
        )
    }

    // -------------------- mergeHits -------------------- //

    @Test
    fun `mergeHits drops other-character vector docs when the query names one`() {
        val cyreneBuild = named("Cyrene", "build", "Cyrene — build recomendada")
        val vector = listOf(
            named("Cipher", "build", "Cipher — build recomendada"),
            named("Castorice", "build", "Castorice — build recomendada"),
            cyreneBuild, // semantic search may also surface the real one
        )
        assertEquals(
            listOf(cyreneBuild),
            GameKnowledgeTools.mergeHits(listOf(cyreneBuild), vector, "qual set usar na cyrene?"),
        )
    }

    @Test
    fun `mergeHits scopes same-entity vector fill to the asked section`() {
        val e2 = named("Acheron", "eidolon", "Acheron — Eidolon 2: Mute Thunder")
        val vector = listOf(
            named("Acheron", "skill", "Acheron — habilidade: Octobolt Flash"),
            named("Acheron", "eidolon", "Acheron — Eidolon 1: Silenced Sky"),
        )
        // "eidolon 2" keeps only E2, even though the vector fill is all same-character docs.
        assertEquals(
            listOf(e2),
            GameKnowledgeTools.mergeHits(listOf(e2), vector, "efeito do eidolon 2 da acheron"),
        )
    }

    @Test
    fun `mergeHits leaves the vector tier alone when nothing was named`() {
        val vector = listOf(
            named("Cipher", "build", "x"),
            named("Castorice", "build", "y"),
        )
        assertEquals(vector, GameKnowledgeTools.mergeHits(emptyList(), vector, "melhor build quantum?"))
    }
}
