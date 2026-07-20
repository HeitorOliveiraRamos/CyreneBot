package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacter
import com.cyrene.knowledge.RosterAnswerService.Entity
import com.cyrene.knowledge.RosterAnswerService.Row
import com.cyrene.knowledge.RosterAnswerService.RosterAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Locks the table-query parsing and render — the "bot writes its own query" ask, done deterministically. */
class RosterAnswerServiceTest {

    private val roster = listOf(
        HsrCharacter("1013", "Herta", "Herta", "A Erudição", "Gelo", 4, "Estação Espacial Herta"),
        HsrCharacter("1401", "The Herta", "A Herta", "A Erudição", "Gelo", 5, "Estação Espacial Herta"),
        HsrCharacter("1001", "March 7th", "7 de Março", "A Preservação", "Gelo", 4, "Expresso Astral"),
        HsrCharacter("1212", "Jingliu", "Jingliu", "A Destruição", "Gelo", 5, "O Luofu do Xianzhou"),
        HsrCharacter("1309", "Robin", "Robin", "A Harmonia", "Físico", 5, "Vigia da Galáxia"),
        HsrCharacter("1512", "Robin • Summeretto", null, "Remembrance", "Wind", 5, "Expresso Astral"),
    )

    private val factions = roster.mapNotNull { it.faccao }
    private fun rows(cs: List<HsrCharacter>, ask: RosterAsk) =
        cs.map { Row(it.baseName, RosterAnswerService.facts(it, ask)) }

    // -------------------- parsing -------------------- //

    @Test
    fun `parses element, limit and randomness`() {
        val ask = RosterAnswerService.wantedRoster("me fala 5 personagens de gelo aleatórios", factions)!!
        assertEquals(Entity.PERSONAGEM, ask.entity)
        assertEquals("Gelo", ask.elemento)
        assertEquals(5, ask.limit)
        assertTrue(ask.random)
    }

    @Test
    fun `parses rarity without mistaking it for the limit`() {
        val ask = RosterAnswerService.wantedRoster("quais personagens 5 estrelas da harmonia", factions)!!
        assertEquals(5, ask.raridade)
        assertEquals("A Harmonia", ask.caminho)
        assertEquals(RosterAnswerService.DEFAULT_LIMIT, ask.limit)
    }

    @Test
    fun `parses a faction count question`() {
        val ask = RosterAnswerService.wantedRoster("quantos membros tem na facção Expresso Astral?", factions)!!
        assertTrue(ask.count)
        assertEquals("Expresso Astral", ask.faccao)
        assertEquals(Entity.PERSONAGEM, ask.entity)
    }

    @Test
    fun `parses an item count question`() {
        val ask = RosterAnswerService.wantedRoster("quantas relíquias tem no total?", factions)!!
        assertEquals(Entity.RELIQUIA, ask.entity)
        assertTrue(ask.count)
    }

    @Test
    fun `an item word wins over the vaguer personagens`() {
        val ask = RosterAnswerService.wantedRoster("me da 5 cones de luz dos personagens da destruição", factions)!!
        assertEquals(Entity.CONE, ask.entity)
        assertEquals("A Destruição", ask.caminho)
    }

    @Test
    fun `needs both an entity word and a filter`() {
        assertNull(RosterAnswerService.wantedRoster("qual o elemento da himeko", factions))
        assertNull(RosterAnswerService.wantedRoster("quais personagens combinam com a march", factions))
        assertNull(RosterAnswerService.wantedRoster("me fala o kit da acheron", factions))
    }

    // -------------------- filtering -------------------- //

    @Test
    fun `filters by element, canonicalizing the english beta spellings`() {
        assertEquals(4, RosterAnswerService.filter(roster, RosterAsk(elemento = "Gelo")).size)
        assertEquals(
            listOf("1512"),
            RosterAnswerService.filter(roster, RosterAsk(elemento = "Vento")).map { it.id },
        )
    }

    @Test
    fun `filters by faction`() {
        val out = RosterAnswerService.filter(roster, RosterAsk(faccao = "Expresso Astral"))
        assertEquals(setOf("1001", "1512"), out.map { it.id }.toSet())
    }

    @Test
    fun `filters combine as a conjunction`() {
        val out = RosterAnswerService.filter(roster, RosterAsk(elemento = "Gelo", raridade = 5))
        assertEquals(setOf("1401", "1212"), out.map { it.id }.toSet())
    }

    // -------------------- render -------------------- //

    @Test
    fun `a small count also names the entries`() {
        val ask = RosterAsk(faccao = "Expresso Astral", count = true)
        val matched = RosterAnswerService.filter(roster, ask)
        val out = RosterAnswerService.render(rows(matched, ask), ask)
        assertTrue(out.startsWith("**Personagens Expresso Astral**: 2"), out)
        assertTrue(out.contains("- 7 de Março"), out)
        assertTrue(out.contains("- Robin • Summeretto"), out)
    }

    @Test
    fun `a large count stays a single line`() {
        val ask = RosterAsk(entity = Entity.RELIQUIA, count = true)
        val out = RosterAnswerService.render(List(32) { Row("Set $it", null) }, ask)
        assertEquals("**Relíquias**: 32", out)
    }

    @Test
    fun `alternate forms collapse into one entry so counts match what a player counts`() {
        // Five Trailblazer Path rows are ONE character to a player; counting rows gave 15 for
        // Expresso Astral where the answer should be 7.
        val tb = (1..5).map { HsrCharacter("800$it", "Trailblazer", "Desbravador", "A Harmonia", "Fogo", 5, "Expresso Astral") }
        val ask = RosterAsk(faccao = "Expresso Astral", count = true)
        val rows = RosterAnswerService.collapseVariants(tb + roster.filter { it.faccao == "Expresso Astral" }, ask) { it.baseName }
        assertEquals(3, rows.size, rows.toString())
        assertTrue(rows.any { it.name == "Desbravador" && it.facts == "5 formas" }, rows.toString())
    }

    @Test
    fun `list is capped and says how many there are in total`() {
        val ask = RosterAsk(elemento = "Gelo", limit = 2)
        val matched = RosterAnswerService.filter(roster, ask)
        val out = RosterAnswerService.render(rows(matched, ask), ask)
        assertTrue(out.startsWith("**Personagens Gelo** (2 de 4)"), out)
        assertEquals(3, out.lines().size, out)
    }

    @Test
    fun `a filtered-on facet is not repeated on every line`() {
        val ask = RosterAsk(caminho = "A Erudição", raridade = 5)
        val out = RosterAnswerService.render(rows(RosterAnswerService.filter(roster, ask), ask), ask)
        assertEquals("- A Herta", out.lines().last(), out)
    }

    @Test
    fun `random shuffles before capping so the sample can vary`() {
        val ask = RosterAsk(elemento = "Gelo", limit = 2, random = true)
        val r = rows(RosterAnswerService.filter(roster, ask), ask)
        val seen = (1..40).map { RosterAnswerService.render(r, ask) }.toSet()
        assertTrue(seen.size > 1, "40 draws of 2 from 4 should not all be identical")
    }
}
