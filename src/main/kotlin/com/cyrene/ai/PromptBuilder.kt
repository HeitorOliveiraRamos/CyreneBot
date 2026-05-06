package com.cyrene.ai

import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.MessageRole
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.stereotype.Component

@Component
class PromptBuilder(private val properties: BotProperties) {

    /**
     * Builds the message list for Ollama: an optional system prompt followed by [history]
     * mapped to Spring AI message types. The system prompt is the configured
     * `bot.personality` optionally joined with a per-call override; when [overrideOnly] is
     * true, the configured personality is ignored entirely (used by moderation, which
     * needs a strict, isolated system prompt).
     */
    fun build(
        history: List<ConversationMessage>,
        extraSystemPrompt: String? = null,
        overrideOnly: Boolean = false,
    ): List<Message> {
        val systemPrompt = effectiveSystemPrompt(extraSystemPrompt, overrideOnly)
        val messages = mutableListOf<Message>()
        if (!systemPrompt.isNullOrBlank()) messages += SystemMessage(systemPrompt)
        history.forEach { messages += it.toSpringAi() }
        return messages
    }

    private fun effectiveSystemPrompt(extra: String?, overrideOnly: Boolean): String? {
        if (overrideOnly) return extra?.takeIf { it.isNotBlank() }
        val base = properties.personality.takeIf { it.isNotBlank() }
        val tail = extra?.takeIf { it.isNotBlank() }
        return when {
            base != null && tail != null -> "$base\n$tail"
            base != null -> base
            else -> tail
        }
    }

    private fun ConversationMessage.toSpringAi(): Message = when (role) {
        MessageRole.USER -> UserMessage(content)
        MessageRole.ASSISTANT -> AssistantMessage(content)
        MessageRole.SYSTEM -> SystemMessage(content)
    }
}
