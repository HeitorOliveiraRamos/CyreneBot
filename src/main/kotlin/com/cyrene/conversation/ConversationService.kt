package com.cyrene.conversation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class ConversationService(
    private val conversations: ConversationRepository,
    private val messages: ConversationMessageRepository,
) {

    @Transactional(readOnly = true)
    fun activeConversation(userId: String, channelId: String): Conversation? =
        conversations.findByUserIdAndChannelIdAndActiveTrue(userId, channelId)

    @Transactional(readOnly = true)
    fun isInActiveSession(userId: String, channelId: String): Boolean =
        activeConversation(userId, channelId) != null

    /**
     * Starts a new conversation for (user, channel). Returns null if one is already active.
     * The opening assistant line is persisted as the first message of the new conversation.
     */
    @Transactional
    fun startSession(userId: String, channelId: String, openingLine: String): Conversation? {
        if (conversations.findByUserIdAndChannelIdAndActiveTrue(userId, channelId) != null) {
            return null
        }
        val conv = conversations.save(Conversation(userId = userId, channelId = channelId))
        messages.save(
            ConversationMessage(
                conversationId = conv.id!!,
                role = MessageRole.ASSISTANT,
                content = openingLine,
            )
        )
        return conv
    }

    /**
     * Ends the active conversation for (user, channel). Returns true if a session was ended.
     * Messages are kept for auditability — a fresh `startSession` opens a new conversation row.
     */
    @Transactional
    fun endSession(userId: String, channelId: String): Boolean {
        val active = conversations.findByUserIdAndChannelIdAndActiveTrue(userId, channelId)
            ?: return false
        active.active = false
        active.endedAt = OffsetDateTime.now()
        conversations.save(active)
        return true
    }

    @Transactional(readOnly = true)
    fun history(conversationId: Long): List<ConversationMessage> =
        messages.findByConversationIdOrderByCreatedAtAsc(conversationId)

    @Transactional
    fun recordUserMessage(conversationId: Long, content: String) {
        messages.save(
            ConversationMessage(
                conversationId = conversationId,
                role = MessageRole.USER,
                content = content,
            )
        )
    }

    @Transactional
    fun recordAssistantMessage(conversationId: Long, content: String): Boolean {
        // Only record if the conversation is still active — protects against late
        // AI replies arriving after the user ran /encerrar-conversa.
        val conv = conversations.findById(conversationId).orElse(null) ?: return false
        if (!conv.active) return false
        messages.save(
            ConversationMessage(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = content,
            )
        )
        return true
    }
}
