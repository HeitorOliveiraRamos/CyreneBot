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
 * answered statelessly. Exchanges are still persisted for auditability.
 *
 * Old rows are pruned by [MentionContextService] once they fall outside the configured
 * retention window.
 */
@Entity
@Table(name = "mensagem_mencao")
class MentionMessage(

    @Column(name = "usuario_id", nullable = false)
    var userId: String,

    @Column(name = "servidor_id")
    var guildId: String?,

    @Column(name = "canal_id", nullable = false)
    var channelId: String,

    @Column(name = "mensagem_usuario", nullable = false, columnDefinition = "TEXT")
    var userMessage: String,

    @Column(name = "resposta_assistente", nullable = false, columnDefinition = "TEXT")
    var assistantReply: String,

    @Column(name = "criado_em", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
