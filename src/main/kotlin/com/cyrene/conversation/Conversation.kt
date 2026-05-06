package com.cyrene.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "conversation")
class Conversation(

    @Column(name = "user_id", nullable = false)
    var userId: String,

    @Column(name = "channel_id", nullable = false)
    var channelId: String,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "started_at", nullable = false)
    var startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "ended_at")
    var endedAt: OffsetDateTime? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
