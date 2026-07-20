package com.cyrene.hsr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the variant-identity contract: a NAME does not identify a character. `nome` is shared by
 * both 7 de Março rows and by all ten Trailblazer rows, so display names must carry the Path when
 * ambiguous and a name match must be narrowable by the Path/Element the user typed.
 *
 * The fixture mirrors the real `personagem_hsr` rows exactly, including the English caminho/
 * elemento the un-localized beta rows carry.
 */
class HsrVariantIdentityTest {

    private val march7Hunt = HsrCharacter("1224", "March 7th", "7 de Março", "A Caça", "Imaginário")
    private val march7Pres = HsrCharacter("1001", "March 7th", "7 de Março", "A Preservação", "Gelo")
    private val tbHarmony = HsrCharacter("8005", "Trailblazer", "Desbravador", "A Harmonia", "Imaginário")
    private val tbPreserve = HsrCharacter("8003", "Trailblazer", "Desbravador", "A Preservação", "Fogo")
    private val herta = HsrCharacter("1013", "Herta", "Herta", "A Erudição", "Gelo")
    private val theHerta = HsrCharacter("1401", "The Herta", "A Herta", "A Erudição", "Gelo")
    private val robinBeta = HsrCharacter("1512", "Robin • Summeretto", null, "Remembrance", "Wind")

    private val roster = listOf(march7Hunt, march7Pres, tbHarmony, tbPreserve, herta, theHerta, robinBeta)

    // -------------------- taxonomy -------------------- //

    @Test
    fun `canonicalizes the english spellings the beta rows carry`() {
        assertEquals("A Recordação", HsrTaxonomy.canonicalPath("Remembrance"))
        assertEquals("Vento", HsrTaxonomy.canonicalElement("Wind"))
        assertEquals("Quântico", HsrTaxonomy.canonicalElement("Quantum"))
        // ...and leaves the already-canonical PT labels alone.
        assertEquals("A Caça", HsrTaxonomy.canonicalPath("A Caça"))
        assertEquals("Gelo", HsrTaxonomy.canonicalElement("Gelo"))
        assertNull(HsrTaxonomy.canonicalPath("A Senda do Nada"))
    }

    @Test
    fun `pulls path and element out of free text, accent-insensitively`() {
        assertEquals("A Caça", HsrTaxonomy.pathIn("me fala o kit da março de caça"))
        assertEquals("A Caça", HsrTaxonomy.pathIn("kit da marco de caca"))
        assertEquals("A Preservação", HsrTaxonomy.pathIn("a march de preservation"))
        assertEquals("Gelo", HsrTaxonomy.elementIn("me da 5 personagens de gelo aleatorios"))
        assertNull(HsrTaxonomy.pathIn("qual a build da acheron"))
    }

    // -------------------- display names -------------------- //

    @Test
    fun `display name carries the path only when the name is shared`() {
        val names = HsrCharacterService.displayNames(roster)
        assertEquals("7 de Março (A Caça)", names["1224"])
        assertEquals("7 de Março (A Preservação)", names["1001"])
        assertEquals("Desbravador (A Harmonia)", names["8005"])
        // Unambiguous names stay bare — "Herta" and "A Herta" are different names.
        assertEquals("Herta", names["1013"])
        assertEquals("A Herta", names["1401"])
        // No PT name yet: falls back to the EN one, still unambiguous.
        assertEquals("Robin • Summeretto", names["1512"])
    }

    // -------------------- disambiguation -------------------- //

    // NOTE: these use the FULL stored name ("7 de março"). Resolving the partial name users
    // actually type ("a março de caça") is name RESOLUTION, not disambiguation — it lands in
    // HsrFuzzyMatchTest with the rest of the Phase 7 fallback.

    @Test
    fun `path in the query picks the right variant`() {
        assertEquals(
            listOf("1224"),
            HsrCharacterService.charactersIn("me fala o kit da 7 de março de caça", roster).map { it.id },
        )
        assertEquals(
            listOf("1001"),
            HsrCharacterService.charactersIn("build da 7 de março da preservação", roster).map { it.id },
        )
        assertEquals(
            listOf("8005"),
            HsrCharacterService.charactersIn("kit do desbravador da harmonia", roster).map { it.id },
        )
    }

    @Test
    fun `element in the query picks the right variant too`() {
        assertEquals(
            listOf("1001"),
            HsrCharacterService.charactersIn("a 7 de março de gelo", roster).map { it.id },
        )
    }

    @Test
    fun `no path or element leaves the ambiguity intact rather than guessing`() {
        val hits = HsrCharacterService.charactersIn("kit da 7 de março", roster).map { it.id }
        assertEquals(setOf("1224", "1001"), hits.toSet())
    }

    @Test
    fun `a path that matches nothing in the group keeps the whole group`() {
        // "A Abundância" is no March 7th's path — filtering would empty the group, so it doesn't.
        val hits = HsrCharacterService.charactersIn("a 7 de março da abundância", roster).map { it.id }
        assertEquals(setOf("1224", "1001"), hits.toSet())
    }

    @Test
    fun `disambiguation is per-name, so an unambiguous character is never filtered out`() {
        // "A Herta" is Erudição; the Hunt cue must narrow only the March group, not drop her.
        val hits = HsrCharacterService
            .charactersIn("compara a herta com a 7 de março de caça", roster).map { it.id }
        assertEquals(setOf("1401", "1224"), hits.toSet())
    }

    @Test
    fun `base and variant names still do not co-fire`() {
        // Regression guard for the existing longest-wins contract (the live "A Herta" bug).
        val hits = HsrCharacterService.charactersIn("build da a herta", roster).map { it.id }
        assertEquals(listOf("1401"), hits)
    }

    @Test
    fun `desbravador and desbravadora do not match each other`() {
        val fem = HsrCharacter("8006", "Trailblazer", "Desbravadora", "A Harmonia", "Imaginário")
        val hits = HsrCharacterService.charactersIn("kit do desbravador da harmonia", roster + fem)
        assertTrue(hits.none { it.id == "8006" }, "feminine row must not match the masculine name")
    }
}
