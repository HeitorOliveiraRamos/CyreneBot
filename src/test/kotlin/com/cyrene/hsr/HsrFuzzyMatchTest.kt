package com.cyrene.hsr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the fallback name resolution: partial names (tier 1, exact and unambiguous) and typos
 * (tier 2, scored and margin-gated). The governing rule is that a MISS is safe — the caller falls
 * through to retrieval — while a WRONG match answers confidently about the wrong character, so
 * every ambiguous case here must resolve to nothing.
 */
class HsrFuzzyMatchTest {

    private val names = listOf(
        "7 de Março", "March 7th",
        "Herta", "A Herta", "The Herta",
        "Dan Heng", "Dan Heng - Embebidor Lunae", "Dan Heng - Permansor Terrae",
        "Acheron", "Jingliu", "Hyacine", "Himeko", "Robin",
        // Names whose WORDS are everyday Portuguese — the false-positive surface.
        "Noite Eterna", "Himeko - Nova", "Vaga-lume", "Cisne Negro",
    )

    private val roster = listOf(
        HsrCharacter("1224", "March 7th", "7 de Março", "A Caça", "Imaginário"),
        HsrCharacter("1001", "March 7th", "7 de Março", "A Preservação", "Gelo"),
        HsrCharacter("1013", "Herta", "Herta", "A Erudição", "Gelo"),
        HsrCharacter("1401", "The Herta", "A Herta", "A Erudição", "Gelo"),
        HsrCharacter("1308", "Acheron", "Acheron", "A Inexistência", "Imaginário"),
    )

    // -------------------- tier 1: partial names -------------------- //

    @Test
    fun `a distinctive word resolves the full name`() {
        assertEquals(listOf("7 de Março"), HsrCharacterService.fuzzyMatch("me fala o kit da março de caça", names))
        assertEquals(
            listOf("Dan Heng - Embebidor Lunae"),
            HsrCharacterService.fuzzyMatch("build do embebidor", names),
        )
        assertEquals(
            listOf("Dan Heng - Permansor Terrae"),
            HsrCharacterService.fuzzyMatch("kit do permansor", names),
        )
    }

    @Test
    fun `a word shared by several names is not distinctive and resolves nothing`() {
        // "heng" belongs to Dan Heng, Embebidor Lunae AND Permansor Terrae — guessing which is
        // how you answer about the wrong character, so tier 1 declines rather than pick one.
        assertEquals(emptyList(), HsrCharacterService.fuzzyMatch("qual o kit do heng", names))
        // Same for the three Hertas.
        assertEquals(emptyList(), HsrCharacterService.fuzzyMatch("build da hert", names))
    }

    // -------------------- tier 2: typos -------------------- //

    @Test
    fun `a typo resolves to the nearest clear name`() {
        assertEquals(listOf("Acheron"), HsrCharacterService.fuzzyMatch("qual a build da acheronn", names))
        assertEquals(listOf("Jingliu"), HsrCharacterService.fuzzyMatch("o kit da jingliuu", names))
        // A dropped separator is matched EXACTLY on the squashed form, not scored — no risk.
        assertEquals(listOf("Vaga-lume"), HsrCharacterService.fuzzyMatch("o kit da vagalume", names))
        assertEquals(listOf("Noite Eterna"), HsrCharacterService.fuzzyMatch("cone noiteeterna", names))
    }

    @Test
    fun `a real word that merely resembles a name is not a typo of it`() {
        // "acheronte" is the mythological river, and it sits CLOSER to "Acheron" (0.75) than
        // some genuine typos do. The tier prefers precision and declines — the caller falls
        // through to retrieval, which is the safe outcome. Locking the trade-off deliberately.
        assertEquals(emptyList(), HsrCharacterService.fuzzyMatch("o acheronte da mitologia", names))
    }

    /**
     * The regression this tier is most likely to cause. Each of these lines is ordinary Portuguese
     * whose words happen to be pieces of a character name; matching any of them would route casual
     * chat into the knowledge pipeline. Found by running the matcher over the real 97-row roster.
     */
    @Test
    fun `everyday portuguese that happens to be part of a name matches nothing`() {
        listOf(
            "alguem ai quer jogar hoje a noite",
            "boa noite gente",
            "acabei de terminar a missao nova",
            "tem vaga no time de voces",
            "o ceu ficou negro de repente",
        ).forEach { line ->
            assertEquals(emptyList(), HsrCharacterService.fuzzyMatch(line, names), "must not match: $line")
        }
    }

    @Test
    fun `nonsense resolves to nothing rather than the nearest name`() {
        assertEquals(emptyList(), HsrCharacterService.fuzzyMatch("qual a build do zzzqqq", names))
        assertEquals(emptyList(), HsrCharacterService.fuzzyMatch("bom dia pessoal tudo certo", names))
        assertEquals(emptyList(), HsrCharacterService.fuzzyMatch("me fala 5 personagens de gelo", names))
    }

    // -------------------- integration with the exact tier -------------------- //

    @Test
    fun `exact matches are never widened by the fallback`() {
        // "acheron" matches exactly, so fuzzy never runs and no near-name rides along.
        val hits = HsrCharacterService.charactersIn("qual a build da acheron", roster)
        assertEquals(listOf("1308"), hits.map { it.id })
    }

    @Test
    fun `partial name plus path resolves the exact variant`() {
        // The whole point: this is the user's own example, and it needs BOTH the fuzzy tier
        // (março → 7 de Março) and the Path disambiguation (caça → the Hunt row).
        val hits = HsrCharacterService.charactersIn("me fala o kit da março de caça", roster)
        assertEquals(listOf("1224"), hits.map { it.id })
    }

    @Test
    fun `partial name without a path still returns both variants`() {
        val hits = HsrCharacterService.charactersIn("me fala o kit da março", roster)
        assertEquals(setOf("1224", "1001"), hits.map { it.id }.toSet())
    }

    @Test
    fun `a chat message that names nobody resolves to nobody`() {
        assertTrue(HsrCharacterService.charactersIn("alguem ai quer jogar hoje?", roster).isEmpty())
    }
}
