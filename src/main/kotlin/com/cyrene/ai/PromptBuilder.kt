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
     *
     * Defends against a blank path (e.g. `BOT_PERSONALITY_FILE=` left empty in `.env` —
     * Spring's `${VAR:default}` syntax only falls back when VAR is *unset*, not when it's
     * empty). A blank path was silently resolving to the classpath ROOT, whose inputStream
     * is a directory listing — that listing was then being sent to Ollama as the persona.
     */
    private val basePersonality: String = run {
        val configured = properties.personalityFile
        val location = configured.takeIf { it.isNotBlank() } ?: DEFAULT_PERSONALITY_LOCATION
        if (configured.isBlank()) {
            log.warn(
                "BotProperties.personalityFile is blank; falling back to '{}'. " +
                    "Check that BOT_PERSONALITY_FILE in your .env is either unset or points to a real resource.",
                DEFAULT_PERSONALITY_LOCATION,
            )
        }
        val resource = resourceLoader.getResource(location)
        require(resource.exists()) { "Personality file not found at: $location" }
        val content = resource.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        require(content.isNotBlank()) { "Personality file is empty: $location" }
        require(content.length >= MIN_PERSONA_LENGTH) {
            "Personality content from '$location' is suspiciously short (${content.length} chars). " +
                "Did the path resolve to a directory or a stub file? Got: ${content.take(200)}"
        }
        log.info("Loaded persona prompt from {} ({} chars)", location, content.length)
        content
    }

    private companion object {
        const val DEFAULT_PERSONALITY_LOCATION = "classpath:prompts/cyrene-personality.md"
        /** Heuristic floor: any real persona file is far longer than this. Catches the
         *  classpath-root directory-listing case (~20-50 chars) before it reaches Ollama. */
        const val MIN_PERSONA_LENGTH = 200
    }

    /**
     * Builds the message list for Ollama: a system prompt followed by [history]
     * mapped to Spring AI message types. The system prompt is the persona loaded
     * from the personality file, optionally joined with a per-call override; when
     * [overrideOnly] is true, the persona is ignored entirely (used by the brain
     * pass, which needs a strict, persona-less reasoning prompt).
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
