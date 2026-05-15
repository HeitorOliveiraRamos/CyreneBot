package com.cyrene.conversation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class ConversationService(
    private val conversations: ConversationRepository,
    private val exchanges: ConversationExchangeRepository,
) {

    @Transactional(readOnly = true)
    fun activeConversation(userId: String, channelId: String): Conversation? =
        conversations.findByUserIdAndChannelIdAndActiveTrue(userId, channelId)

    @Transactional(readOnly = true)
    fun isInActiveSession(userId: String, channelId: String): Boolean =
        activeConversation(userId, channelId) != null

    /**
     * Starts a new conversation for (user, channel). Returns null if one is already
     * active. The opening assistant line is persisted as the first exchange of the new
     * conversation with a null `user_message` (no user counterpart for the greeting).
     */
    @Transactional
    fun startSession(userId: String, channelId: String, openingLine: String): Conversation? {
        if (conversations.findByUserIdAndChannelIdAndActiveTrue(userId, channelId) != null) {
            return null
        }
        val conv = conversations.save(Conversation(userId = userId, channelId = channelId))
        exchanges.save(
            ConversationExchange(
                conversationId = conv.id!!,
                userMessage = null,
                assistantReply = openingLine,
            )
        )
        return conv
    }

    /**
     * Ends the active conversation for (user, channel). Returns true if a session was
     * ended. Exchanges are kept for auditability — a fresh [startSession] opens a new
     * conversation row.
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

    /**
     * Flattens stored exchanges back into a sequence of role-tagged [ConversationMessage]s
     * for the AI layer. An exchange with a null `userMessage` (the opening greeting)
     * yields only the assistant turn.
     */
    @Transactional(readOnly = true)
    fun history(conversationId: Long): List<ConversationMessage> {
        val rows = exchanges.findByConversationIdOrderByCreatedAtAsc(conversationId)
        val out = ArrayList<ConversationMessage>(rows.size * 2)
        for (row in rows) {
            row.userMessage?.let {
                out += ConversationMessage(
                    conversationId = conversationId,
                    role = MessageRole.USER,
                    content = it,
                    createdAt = row.createdAt,
                )
            }
            out += ConversationMessage(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = row.assistantReply,
                createdAt = row.createdAt,
            )
        }
        return out
    }

    /**
     * Records a full question/answer pair as a single row. Returns false if the
     * conversation is no longer active (protects against late AI replies arriving after
     * the user ran `/encerrar-conversa`).
     */
    @Transactional
    fun recordExchange(conversationId: Long, userMessage: String, assistantReply: String): Boolean {
        val conv = conversations.findById(conversationId).orElse(null) ?: return false
        if (!conv.active) return false
        exchanges.save(
            ConversationExchange(
                conversationId = conversationId,
                userMessage = userMessage,
                assistantReply = assistantReply,
            )
        )
        return true
    }
}
