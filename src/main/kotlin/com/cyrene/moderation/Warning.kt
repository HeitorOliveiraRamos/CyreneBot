package com.cyrene.moderation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "moderation_warning")
class Warning(

    @Column(name = "user_id", nullable = false)
    var userId: String,

    @Column(name = "guild_id", nullable = false)
    var guildId: String,

    @Column(name = "reason", columnDefinition = "TEXT")
    var reason: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
