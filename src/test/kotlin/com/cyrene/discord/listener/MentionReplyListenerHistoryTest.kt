package com.cyrene.discord.listener

import com.cyrene.conversation.MessageRole
import com.cyrene.discord.ChainEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the reply-chain → LLM-history mapping in isolation (no live JDA). The prefixing
 * contract matters: the voice model relies on `[name]:` tags to tell speakers apart in
 * multi-user threads, while Cyrene's own turns stay bare so they read as assistant output.
 */
class MentionReplyListenerHistoryTest {

    @Test
    fun `prefixes human turns with the name and leaves Cyrene's turns bare`() {
        val chain = listOf(
            ChainEntry(authorName = "Heitor", content = "oi", role = MessageRole.USER),
            ChainEntry(authorName = "Cyrene", content = "Olá, amor.", role = MessageRole.ASSISTANT),
        )

        val out = MentionReplyListener.historyFromChain(chain, "Heitor", "tudo bem?")

        assertEquals(3, out.size)
        assertEquals(MessageRole.USER, out[0].role)
        assertEquals("[Heitor]: oi", out[0].content)
        assertEquals(MessageRole.ASSISTANT, out[1].role)
        assertEquals("Olá, amor.", out[1].content)
        // The current user turn is appended last, also name-prefixed.
        assertEquals(MessageRole.USER, out[2].role)
        assertEquals("[Heitor]: tudo bem?", out[2].content)
    }

    @Test
    fun `flags third-party bot turns distinctly`() {
        val chain = listOf(
            ChainEntry(authorName = "MEE6", content = "rank", role = MessageRole.USER, isOtherBot = true),
        )

        val out = MentionReplyListener.historyFromChain(chain, "Heitor", "e aí?")

        assertEquals("[outro bot MEE6]: rank", out[0].content)
    }

    @Test
    fun `an empty chain still appends the current user turn`() {
        val out = MentionReplyListener.historyFromChain(emptyList(), "Ana", "olá")

        assertEquals(1, out.size)
        assertEquals(MessageRole.USER, out[0].role)
        assertEquals("[Ana]: olá", out[0].content)
    }
}
