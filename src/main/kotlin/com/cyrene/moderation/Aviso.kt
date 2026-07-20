package com.cyrene.moderation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * Uma advertência registrada por um moderador via `/avisar`.
 *
 * Escopo por SERVIDOR (ao contrário de [com.cyrene.conversation.Usuario], que é global): um
 * aviso dado num servidor não conta em outro. Nada expira — o histórico só serve se for
 * completo.
 */
@Entity
@Table(name = "aviso")
class Aviso(

    @Column(name = "guild_id", nullable = false)
    var guildId: String,

    @Column(name = "usuario_id", nullable = false)
    var usuarioId: String,

    /** Quem emitiu o aviso, guardado para auditoria. */
    @Column(name = "moderador_id", nullable = false)
    var moderadorId: String,

    @Column(name = "motivo", nullable = false, columnDefinition = "TEXT")
    var motivo: String,

    @Column(name = "criado_em", nullable = false)
    var criadoEm: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)

@Repository
interface AvisoRepository : JpaRepository<Aviso, Long> {

    /** Histórico de uma pessoa neste servidor, mais recentes primeiro. */
    fun findByGuildIdAndUsuarioIdOrderByCriadoEmDesc(guildId: String, usuarioId: String): List<Aviso>

    fun countByGuildIdAndUsuarioId(guildId: String, usuarioId: String): Long
}
