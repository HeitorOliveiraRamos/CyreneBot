package com.cyrene.discord.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the snowflake validation that underpins the moderation safety model: targets are
 * accepted only as numeric Discord ids, never by name, so the model can't act on the wrong
 * user when display names collide.
 */
class DiscordToolsTest {

    @Test
    fun `accepts a typical discord snowflake`() {
        assertTrue(DiscordTools.isSnowflake("123456789012345678"))
    }

    @Test
    fun `rejects empty, too-short, non-numeric, mention-wrapped, and overlong ids`() {
        assertFalse(DiscordTools.isSnowflake(""))
        assertFalse(DiscordTools.isSnowflake("123"))           // shorter than 5
        assertFalse(DiscordTools.isSnowflake("12a456"))        // contains a non-digit
        assertFalse(DiscordTools.isSnowflake("<@123456789>"))  // mention syntax, not a bare id
        assertFalse(DiscordTools.isSnowflake("1".repeat(21)))  // longer than 20
    }

    @Test
    fun `accepts the boundary lengths`() {
        assertTrue(DiscordTools.isSnowflake("12345"))          // exactly 5
        assertTrue(DiscordTools.isSnowflake("1".repeat(20)))   // exactly 20
    }
}
