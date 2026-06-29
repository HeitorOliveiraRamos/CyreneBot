package com.cyrene.discord.util

import kotlin.test.Test
import kotlin.test.assertTrue

/** Guards the in-persona fallback copy against dropped interpolation or empty strings. */
class BotMessagesTest {

    @Test
    fun `cooldown message includes the wait seconds`() {
        assertTrue(BotMessages.cooldown(7).contains("7"))
    }

    @Test
    fun `busy message addresses the user by name`() {
        assertTrue(BotMessages.busy("Heitor").contains("Heitor"))
    }

    @Test
    fun `static fallback messages are non-blank`() {
        assertTrue(BotMessages.ERROR.isNotBlank())
        assertTrue(BotMessages.BUSY_SESSION.isNotBlank())
    }
}
