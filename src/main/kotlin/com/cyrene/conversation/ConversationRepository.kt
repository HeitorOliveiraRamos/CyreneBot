package com.cyrene.conversation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ConversationRepository : JpaRepository<Conversation, Long> {
    fun findByUserIdAndChannelIdAndActiveTrue(userId: String, channelId: String): Conversation?
}

@Repository
interface ConversationExchangeRepository : JpaRepository<ConversationExchange, Long> {
    fun findByConversationIdOrderByCreatedAtAsc(conversationId: Long): List<ConversationExchange>
    fun deleteByConversationId(conversationId: Long)
}
