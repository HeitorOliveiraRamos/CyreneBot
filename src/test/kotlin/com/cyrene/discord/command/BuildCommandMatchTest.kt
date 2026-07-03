package com.cyrene.discord.command

import com.cyrene.hsr.MihomoCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The /build character matcher: accent/case-insensitive, refuses ambiguous guesses. */
class BuildCommandMatchTest {

    private fun char(name: String) = MihomoCharacter(
        id = "1", name = name, level = 80, eidolon = 0, pathName = "", elementName = "",
        lightCone = null, relics = emptyList(), relicSets = emptyList(),
    )

    private val showcase = listOf(char("Acheron"), char("Março 7"), char("Dan Heng • Lua Imbibitora"))

    @Test
    fun `matches exact name ignoring case and accents`() {
        assertEquals("Acheron", BuildCommand.matchCharacter("acheron", showcase)?.name)
        assertEquals("Março 7", BuildCommand.matchCharacter("marco 7", showcase)?.name)
    }

    @Test
    fun `matches by containment when unambiguous`() {
        assertEquals("Dan Heng • Lua Imbibitora", BuildCommand.matchCharacter("dan heng", showcase)?.name)
    }

    @Test
    fun `returns null on ambiguity or no match rather than guessing`() {
        val twoDans = showcase + char("Dan Heng")
        // "dan" contains-matches both Dan Hengs → refuse to guess.
        assertNull(BuildCommand.matchCharacter("dan", twoDans))
        assertNull(BuildCommand.matchCharacter("kafka", showcase))
        assertNull(BuildCommand.matchCharacter("   ", showcase))
    }
}
