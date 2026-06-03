package com.cyrene.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * A single durable thing the bot has been asked to remember about a user (e.g. a
 * preference, an important date, a "don't do X"). Saved by the LLM mid-conversation via
 * [com.cyrene.discord.tools.UserMemoryTools.rememberAboutUser] and recalled into the
 * prompt on the user's next interaction.
 *
 * Unlike [UserProfile.personalitySummary] these are kept verbatim and are not rewritten by
 * the periodic summarizer. Only created when the user has memory enabled.
 */
@Entity
@Table(name = "fato_usuario")
class UserFact(

    @Column(name = "usuario_id", nullable = false)
    var userId: String,

    @Column(name = "conteudo", nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "criado_em", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
