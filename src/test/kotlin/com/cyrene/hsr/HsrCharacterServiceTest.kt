package com.cyrene.hsr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Locks the pure name-resolution core and the recommendation rendering. */
class HsrCharacterServiceTest {

    private fun char(id: String, en: String?, pt: String? = null) =
        HsrCharacter(id, nameEn = en, namePt = pt)

    private val chars = listOf(
        char("1308", "Acheron", "Acheron"),
        char("1310", "Firefly", "Vagalume"),
        char("1212", "Jingliu", "Jingliu"),
        char("1213", "Dan Heng • Imbibitor Lunae", "Dan Heng - Lua Imbibitora"),
        char("1002", "Dan Heng", "Dan Heng"),
    )

    @Test
    fun `resolve matches pt or en, accent-insensitively`() {
        assertEquals("1310", HsrCharacterService.resolve("firefly", chars))
        assertEquals("1310", HsrCharacterService.resolve("VAGALUME", chars))
    }

    @Test
    fun `resolve prefers exact match over containment`() {
        // "dan heng" is exact for 1002 even though it's contained in 1213's names
        assertEquals("1002", HsrCharacterService.resolve("dan heng", chars))
        assertEquals("1213", HsrCharacterService.resolve("lua imbibitora", chars))
    }

    @Test
    fun `resolve refuses ambiguity and blanks`() {
        assertNull(HsrCharacterService.resolve("dan", chars))
        assertNull(HsrCharacterService.resolve("   ", chars))
        assertNull(HsrCharacterService.resolve("kafka", chars))
    }

    @Test
    fun `charactersIn finds names in free text, any language, accent-insensitive`() {
        assertEquals(listOf("1308"), HsrCharacterService.charactersIn("quem é a acheron?", chars).map { it.id })
        assertEquals(listOf("1310"), HsrCharacterService.charactersIn("o kit do VAGALUME é bom?", chars).map { it.id })
        // Several characters in one message; multi-word names must appear whole
        // (just "lua imbibitora" does not fire 1213's "Dan Heng - Lua Imbibitora").
        assertEquals(
            listOf("1308", "1002"),
            HsrCharacterService.charactersIn("acheron ou dan heng, quem bate mais?", chars).map { it.id },
        )
    }

    @Test
    fun `charactersIn fires only the SP form when its full name is used`() {
        // Stored with " - "/"•" separators the user never types: punctuation-folded match.
        assertEquals(
            listOf("1213"),
            HsrCharacterService.charactersIn("o kit do dan heng lua imbibitora é bom?", chars).map { it.id },
        )
        // Naming base AND form fires both — the bare mention sits outside the SP span.
        assertEquals(
            listOf("1213", "1002"),
            HsrCharacterService.charactersIn("dan heng ou dan heng lua imbibitora?", chars).map { it.id },
        )
    }

    @Test
    fun `expandNicknames turns il and pt shorthands into the canonical variant name`() {
        assertEquals(
            "qual a build do Dan Heng - Embebidor Lunae?",
            HsrCharacterService.expandNicknames("qual a build do dan heng il?"),
        )
        // Case-insensitive; the rest of the query is left verbatim.
        assertEquals(
            "build do Dan Heng - Permansor Terrae",
            HsrCharacterService.expandNicknames("build do DAN HENG PT"),
        )
        // Base Dan Heng and unrelated "il"/"pt" tokens stay untouched.
        assertEquals("build do dan heng", HsrCharacterService.expandNicknames("build do dan heng"))
        assertEquals("ele fala pt br", HsrCharacterService.expandNicknames("ele fala pt br"))
    }

    @Test
    fun `resolve matches SP names typed without the stored punctuation`() {
        assertEquals("1213", HsrCharacterService.resolve("dan heng lua imbibitora", chars))
    }

    @Test
    fun `charactersIn requires whole words — no substring hits`() {
        // "acheronte" must not fire "Acheron" (the roster guard's "cora/coração" lesson).
        assertTrue(HsrCharacterService.charactersIn("o acheronte da mitologia", chars).isEmpty())
        assertTrue(HsrCharacterService.charactersIn("papo qualquer sem personagens", chars).isEmpty())
    }

    @Test
    fun `charactersIn skips stoplisted and short names`() {
        val risky = listOf(
            char("9001", "Pela", "Pela"),
            char("9002", "Sunday", "Domingo"),
        )
        // "pela" and "domingo" are everyday Portuguese words — never gazetteer hits.
        assertTrue(HsrCharacterService.charactersIn("ela passou pela loja no domingo?", risky).isEmpty())
        // They still resolve normally when the input IS a name (/build path).
        assertEquals("9001", HsrCharacterService.resolve("pela", risky))
    }

    @Test
    fun `containsWord matches on word boundaries only`() {
        assertTrue(HsrCharacterService.containsWord("quem e a acheron?", "acheron"))
        assertTrue(HsrCharacterService.containsWord("acheron", "acheron"))
        assertEquals(false, HsrCharacterService.containsWord("acheronte", "acheron"))
        assertEquals(false, HsrCharacterService.containsWord("xacheron", "acheron"))
        assertEquals(false, HsrCharacterService.containsWord("texto qualquer", ""))
    }

    @Test
    fun `renderRecommendations marks matching sets and mains`() {
        val meta = FribbelsMeta(
            subWeights = mapOf("CriticalChanceBase" to 1.0),
            mainStats = mapOf(3 to listOf("CriticalChanceBase", "CriticalDamageBase"), 4 to listOf("SpeedDelta")),
            relicSets = listOf(listOf("Poeta do Colapso do Luto", "Poeta do Colapso do Luto")),
            ornamentSets = listOf("Demesne Serena da Coleção de Ossos"),
            substatPriority = listOf("CriticalDamageBase", "CriticalChanceBase"),
        )
        val character = MihomoCharacter(
            id = "1407", name = "Castorice", level = 80, eidolon = 0,
            pathName = "Lembrança", elementName = "Quântico", lightCone = null,
            relics = listOf(
                MihomoRelic(
                    name = "x", slot = 3, setName = "Poeta do Colapso do Luto", rarity = 5, level = 15,
                    mainAffix = MihomoAffix("CriticalChanceBase", "Chance Crít.", "x"), subAffixes = emptyList(),
                ),
                MihomoRelic(
                    name = "x", slot = 4, setName = "Poeta do Colapso do Luto", rarity = 5, level = 15,
                    mainAffix = MihomoAffix("HPAddedRatio", "PV%", "x"), subAffixes = emptyList(),
                ),
            ),
            relicSets = listOf(MihomoRelicSet("Poeta do Colapso do Luto", 4)),
        )
        val out = BuildAnalyzer.renderRecommendations(character, meta)
        assertTrue("4pç Poeta do Colapso do Luto ✓" in out, out)
        // ornament recommended but not worn: listed without a check mark
        assertTrue("Demesne Serena da Coleção de Ossos" in out && "Demesne Serena da Coleção de Ossos ✓" !in out, out)
        assertTrue("Corpo: Chance Crít./Dano Crít. ✓" in out, out)
        assertTrue("Pés: Velocidade ✗" in out, out)
        assertTrue("Dano Crít. > Chance Crít." in out, out)
    }
}
