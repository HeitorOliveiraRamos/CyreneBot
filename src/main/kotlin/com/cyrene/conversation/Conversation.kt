package com.cyrene.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "conversa")
class Conversation(

    @Column(name = "usuario_id", nullable = false)
    var userId: String,

    @Column(name = "canal_id", nullable = false)
    var channelId: String,

    @Column(name = "ativa", nullable = false)
    var active: Boolean = true,

    @Column(name = "iniciada_em", nullable = false)
    var startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "encerrada_em")
    var endedAt: OffsetDateTime? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
