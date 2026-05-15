package com.cyrene.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * One full question/answer turn in a `/iniciar-conversa` session. Replaces the legacy
 * per-role rows in `conversation_message`. Matches the shape of [MentionMessage] so both
 * persistence paths look the same.
 *
 * [userMessage] is nullable because the opening greeting recorded by
 * [ConversationService.startSession] has no user counterpart — only the assistant's
 * opening line. When [ConversationService.history] flattens rows back into a sequence of
 * [ConversationMessage] for the prompt, a null `userMessage` produces only the assistant
 * turn.
 */
@Entity
@Table(name = "conversation_exchange")
class ConversationExchange(

    @Column(name = "conversation_id", nullable = false)
    var conversationId: Long,

    @Column(name = "user_message", columnDefinition = "TEXT")
    var userMessage: String?,

    @Column(name = "assistant_reply", nullable = false, columnDefinition = "TEXT")
    var assistantReply: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
