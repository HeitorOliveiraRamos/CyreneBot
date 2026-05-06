package com.cyrene.ai

import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.MessageRole
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.stereotype.Service

/**
 * Thin wrapper over Spring AI's [OllamaChatModel] that encapsulates prompt assembly and
 * response post-processing. Throws on transport/API failures — callers decide how to react.
 */
@Service
class OllamaAiService(
    private val chatModel: OllamaChatModel,
    private val promptBuilder: PromptBuilder,
    private val postProcessor: ResponsePostProcessor,
) {

    /**
     * Chat using a full conversation [history] (including the latest user turn).
     */
    fun chat(
        history: List<ConversationMessage>,
        extraSystemPrompt: String? = null,
        overrideSystemOnly: Boolean = false,
    ): String {
        val messages = promptBuilder.build(history, extraSystemPrompt, overrideSystemOnly)
        val response = chatModel.call(Prompt(messages))
        val raw = response.result.output.text ?: ""
        return postProcessor.process(raw)
    }

    /**
     * One-shot chat: no prior history, a single user message.
     */
    fun chatOnce(
        userMessage: String,
        extraSystemPrompt: String? = null,
        overrideSystemOnly: Boolean = false,
    ): String = chat(
        history = listOf(
            ConversationMessage(
                conversationId = 0L,
                role = MessageRole.USER,
                content = userMessage,
            )
        ),
        extraSystemPrompt = extraSystemPrompt,
        overrideSystemOnly = overrideSystemOnly,
    )
}
