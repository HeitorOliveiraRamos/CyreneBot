package com.cyrene.conversation

import java.time.OffsetDateTime

enum class MessageRole { USER, ASSISTANT, SYSTEM }

/**
 * In-memory chat turn passed between the conversation layer, [com.cyrene.ai.PromptBuilder],
 * and [com.cyrene.ai.OllamaAiService]. It is NOT a JPA entity anymore — chat sessions
 * persist combined exchanges (see [ConversationExchange]) and `ConversationService`
 * flattens them back into a sequence of these DTOs when building history for the AI.
 *
 * `conversationId` is kept for parity with prior code; callers that don't have a
 * conversation in scope (mention path, channel-context command) pass 0L.
 */
data class ConversationMessage(
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
