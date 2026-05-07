package com.cyrene.ai

import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.MessageRole
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

@Component
class PromptBuilder(
    private val properties: BotProperties,
    resourceLoader: ResourceLoader,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Base persona prompt loaded once at startup from [BotProperties.personalityFile].
     * Resolved via Spring's [ResourceLoader] so values can be `classpath:...` or `file:...`.
     */
    private val basePersonality: String = run {
        val location = properties.personalityFile
        val resource = resourceLoader.getResource(location)
        require(resource.exists()) { "Personality file not found at: $location" }
        val content = resource.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        require(content.isNotBlank()) { "Personality file is empty: $location" }
        log.info("Loaded persona prompt from {} ({} chars)", location, content.length)
        content
    }

    /**
     * Builds the message list for Ollama: a system prompt followed by [history]
     * mapped to Spring AI message types. The system prompt is the persona loaded
     * from the personality file, optionally joined with a per-call override; when
     * [overrideOnly] is true, the persona is ignored entirely (used by moderation,
     * which needs a strict, isolated system prompt).
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
        val tail = extra?.takeIf { it.isNotBlank() }
        return if (tail != null) "$basePersonality\n$tail" else basePersonality
    }

    private fun ConversationMessage.toSpringAi(): Message = when (role) {
        MessageRole.USER -> UserMessage(content)
        MessageRole.ASSISTANT -> AssistantMessage(content)
        MessageRole.SYSTEM -> SystemMessage(content)
    }
}
