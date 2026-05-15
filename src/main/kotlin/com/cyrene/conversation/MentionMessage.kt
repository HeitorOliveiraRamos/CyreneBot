package com.cyrene.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * One full question/answer exchange between a Discord user and the bot via @-mention.
 *
 * The prompt path no longer feeds prior mention history back to the LLM — each mention is
 * answered statelessly. We still persist the exchanges so [UserInfoService] can
 * periodically re-summarize the user's personality from the last N exchanges.
 *
 * Old rows are pruned by [MentionContextService] once they fall outside the configured
 * retention window.
 */
@Entity
@Table(name = "mention_message")
class MentionMessage(

    @Column(name = "user_id", nullable = false)
    var userId: String,

    @Column(name = "guild_id")
    var guildId: String?,

    @Column(name = "channel_id", nullable = false)
    var channelId: String,

    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT")
    var userMessage: String,

    @Column(name = "assistant_reply", nullable = false, columnDefinition = "TEXT")
    var assistantReply: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
