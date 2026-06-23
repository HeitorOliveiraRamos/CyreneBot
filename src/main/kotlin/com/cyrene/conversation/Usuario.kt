package com.cyrene.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * Linha global por usuário do Discord (não por servidor).
 *
 * [nome] é o nome efetivo do usuário (o que ele deu a si mesmo), atualizado a cada interação
 * direto da JDA. [memoria] é um texto livre que o próprio usuário escolhe que o bot guarde,
 * preenchido pelo comando `/memoria` ([com.cyrene.discord.command.MemoryCommand]); fica nulo
 * até o usuário definir algo. Cargo e permissões NÃO são persistidos — variam por servidor e
 * são lidos ao vivo da JDA quando necessário.
 */
@Entity
@Table(name = "usuario")
class Usuario(

    @Column(name = "usuario_id", nullable = false, unique = true)
    var usuarioId: String,

    @Column(name = "nome", nullable = false)
    var nome: String,

    @Column(name = "memoria", columnDefinition = "TEXT")
    var memoria: String? = null,

    @Column(name = "criado_em", nullable = false)
    var criadoEm: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "atualizado_em", nullable = false)
    var atualizadoEm: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
