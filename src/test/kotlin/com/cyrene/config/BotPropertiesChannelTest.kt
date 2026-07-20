package com.cyrene.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The channel allow-list ([BotProperties.allowsChannel]) gates both the message listeners
 * and every slash command, so an off-by-one here silently takes the bot offline (or leaves
 * it wide open). Pure function, tested without a Spring context.
 */
class BotPropertiesChannelTest {

    private fun props(ids: List<String>) = BotProperties(token = "t", modelName = "m", testChannelIds = ids)

    @Test
    fun `an empty allow-list permits every channel`() {
        val p = props(emptyList())
        assertTrue(p.allowsChannel("123", isFromGuild = true))
        assertTrue(p.allowsChannel("999", isFromGuild = true))
    }

    @Test
    fun `a populated allow-list permits only the listed guild channels`() {
        val p = props(listOf("123", "456"))
        assertTrue(p.allowsChannel("123", isFromGuild = true))
        assertTrue(p.allowsChannel("456", isFromGuild = true))
        assertFalse(p.allowsChannel("789", isFromGuild = true))
    }

    @Test
    fun `DMs are never gated, so an allow-list can't lock the bot out of private chats`() {
        val p = props(listOf("123"))
        assertTrue(p.allowsChannel("some-dm-channel", isFromGuild = false))
    }

    @Test
    fun `blank entries are ignored, so an unset env var and a trailing comma both mean no restriction`() {
        // TEST_CHANNEL_IDS= binds as a single empty string, not an empty list.
        assertTrue(props(listOf("")).allowsChannel("789", isFromGuild = true))
        // "123," binds with a trailing blank; the real id must still be the only one allowed.
        val p = props(listOf("123", ""))
        assertTrue(p.allowsChannel("123", isFromGuild = true))
        assertFalse(p.allowsChannel("789", isFromGuild = true))
    }
}
