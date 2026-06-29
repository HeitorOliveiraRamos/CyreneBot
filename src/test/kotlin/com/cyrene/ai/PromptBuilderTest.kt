package com.cyrene.ai

import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.MessageRole
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.core.io.DefaultResourceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the prompt assembly contract: persona inclusion/exclusion, extra-prompt joining,
 * `{nome}` substitution (the only sanctioned way a user name reaches the model), and
 * history → Spring AI message-type mapping. Loads the real persona from the classpath.
 */
class PromptBuilderTest {

    private fun builder(): PromptBuilder {
        val props = BotProperties(token = "test", modelName = "llama3.1")
        return PromptBuilder(props, DefaultResourceLoader())
    }

    private fun userTurn(text: String) = ConversationMessage(0L, MessageRole.USER, text)

    @Test
    fun `build prepends a persona system message, then maps history`() {
        val msgs = builder().build(listOf(userTurn("oi")))
        assertTrue(msgs.first() is SystemMessage)
        assertTrue((msgs.first().text?.length ?: 0) >= 200, "persona should be loaded")
        assertTrue(msgs[1] is UserMessage)
        assertEquals("oi", msgs[1].text)
    }

    @Test
    fun `overrideOnly replaces the persona with the extra prompt only`() {
        val msgs = builder().build(
            history = listOf(userTurn("x")),
            extraSystemPrompt = "INSTRUCOES DO BRAIN",
            overrideOnly = true,
        )
        assertEquals("INSTRUCOES DO BRAIN", msgs.first().text)
    }

    @Test
    fun `the extra prompt is appended below the persona when not overrideOnly`() {
        val msgs = builder().build(
            history = listOf(userTurn("x")),
            extraSystemPrompt = "EXTRA",
        )
        val sys = msgs.first().text ?: ""
        assertTrue(sys.length > "EXTRA".length + 200, "persona + extra should both be present")
        assertTrue(sys.endsWith("EXTRA"))
    }

    @Test
    fun `the name token is substituted with the user name`() {
        val msgs = builder().build(
            history = listOf(userTurn("x")),
            extraSystemPrompt = "Oi {nome}.",
            overrideOnly = true,
            userName = "Heitor",
        )
        assertEquals("Oi Heitor.", msgs.first().text)
    }

    @Test
    fun `the name token falls back to a generic vocative when no name is given`() {
        val msgs = builder().build(
            history = listOf(userTurn("x")),
            extraSystemPrompt = "Oi {nome}.",
            overrideOnly = true,
            userName = null,
        )
        assertEquals("Oi amor.", msgs.first().text)
    }

    @Test
    fun `history roles map to matching Spring AI message types`() {
        val msgs = builder().build(
            listOf(
                ConversationMessage(0L, MessageRole.USER, "pergunta"),
                ConversationMessage(0L, MessageRole.ASSISTANT, "resposta"),
            ),
        )
        // [0] persona system, [1] user, [2] assistant
        assertTrue(msgs[1] is UserMessage)
        assertTrue(msgs[2] is AssistantMessage)
    }
}
