package com.cyrene.conversation

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Dono da memória de usuário do bot. Há dois tipos de dado que passam por aqui:
 *
 *  - **Identidade ao vivo** (nome, cargo mais alto, permissões de moderação): lida fresca da
 *    JDA a cada interação e injetada no prompt. Cargo/permissões NUNCA são persistidos — variam
 *    por servidor e são baratos de buscar. Só o nome efetivo é guardado (em [Usuario.nome]),
 *    atualizado a cada interação.
 *
 *  - **Memória persistida** ([Usuario.memoria]): um texto livre que o próprio usuário escolhe
 *    que o bot guarde, definido pelo comando `/memoria`. Fica nulo até o usuário definir algo.
 *
 * A autoridade das ferramentas de moderação é independente disso: [com.cyrene.discord.tools.DiscordTools]
 * re-verifica as permissões do chamador ao vivo na JDA antes de qualquer ação destrutiva.
 */
@Service
class UsuarioService(
    private val repository: UsuarioRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Monta o bloco de sistema para uma mensagem recebida: identidade ao vivo (sempre) mais o
     * bloco de memória (quando o usuário definiu um). De quebra, faz o upsert da linha do
     * usuário (nome + atualizado_em). Retorna null em falha transitória da JDA — quem chama
     * deve ainda assim responder, só sem o contexto do usuário.
     */
    fun resolveForEvent(event: MessageReceivedEvent): ResolvedUsuario? {
        return try {
            val usuarioId = event.author.id
            val effectiveName: String
            val identityBlock: String
            if (event.isFromGuild) {
                val guild = event.guild
                val member = event.member ?: guild.retrieveMemberById(usuarioId).complete()
                // Nome global que o usuário deu a si mesmo, ignorando o apelido do servidor.
                effectiveName = event.author.effectiveName
                identityBlock = renderGuildIdentity(member, effectiveName)
            } else {
                effectiveName = event.author.effectiveName
                identityBlock = renderDmIdentity()
            }

            val usuario = upsert(usuarioId, effectiveName)
            val memoryBlock = renderMemory(usuario.memoria, effectiveName)

            val systemPrompt = listOfNotNull(
                "O usuário com quem você está falando se chama $effectiveName.",
                identityBlock,
                memoryBlock,
            ).joinToString("\n\n")

            ResolvedUsuario(effectiveName, systemPrompt)
        } catch (e: Exception) {
            log.warn("resolveForEvent falhou para usuário={} canal={}", event.author.id, event.channel.id, e)
            null
        }
    }

    /** O texto de memória atual do usuário, para pré-preencher o modal de `/memoria`. */
    @Transactional(readOnly = true)
    fun memoriaDe(usuarioId: String): String? =
        repository.findByUsuarioId(usuarioId)?.memoria

    /** Define (ou substitui) a memória do usuário. Cria a linha se ainda não existir. */
    @Transactional
    fun salvarMemoria(usuarioId: String, nome: String, texto: String) {
        val usuario = upsert(usuarioId, nome)
        usuario.memoria = texto
        usuario.atualizadoEm = OffsetDateTime.now()
        repository.save(usuario)
    }

    /** O UID de HSR vinculado pelo usuário via /uid, ou null. */
    @Transactional(readOnly = true)
    fun uidDe(usuarioId: String): String? =
        repository.findByUsuarioId(usuarioId)?.uidHsr

    /** Vincula (ou substitui) o UID de HSR do usuário. Cria a linha se ainda não existir. */
    @Transactional
    fun salvarUid(usuarioId: String, nome: String, uid: String) {
        val usuario = upsert(usuarioId, nome)
        usuario.uidHsr = uid
        usuario.atualizadoEm = OffsetDateTime.now()
        repository.save(usuario)
    }

    /** Apaga a memória do usuário (mantém a linha com nome/ids). Idempotente. */
    @Transactional
    fun limparMemoria(usuarioId: String, nome: String) {
        val usuario = upsert(usuarioId, nome)
        if (usuario.memoria != null) {
            usuario.memoria = null
            usuario.atualizadoEm = OffsetDateTime.now()
            repository.save(usuario)
        }
    }

    /**
     * Busca a linha do usuário, criando-a na primeira interação. Sempre mantém [Usuario.nome]
     * em dia com o nome efetivo atual e bumpa [Usuario.atualizadoEm].
     */
    @Transactional
    fun upsert(usuarioId: String, nome: String): Usuario {
        val existente = repository.findByUsuarioId(usuarioId)
        if (existente != null) {
            if (existente.nome != nome) existente.nome = nome
            existente.atualizadoEm = OffsetDateTime.now()
            return repository.save(existente)
        }
        return try {
            repository.save(Usuario(usuarioId = usuarioId, nome = nome))
        } catch (e: Exception) {
            // Primeira interação concorrente ganhou a corrida do insert; re-busca o vencedor.
            log.debug("Criação de usuário correu para {}, buscando existente", usuarioId, e)
            repository.findByUsuarioId(usuarioId) ?: throw e
        }
    }

    private fun renderGuildIdentity(member: Member, nome: String): String = buildString {
        appendLine("## Sobre o usuário com quem você está falando")
        appendLine("- Nome: $nome")
        member.roles.maxByOrNull { it.position }?.let { appendLine("- Cargo mais alto: ${it.name}") }
        appendLine("- Permissões de moderação: ${renderPermissions(member)}")
    }.trimEnd()

    private fun renderDmIdentity(): String = buildString {
        appendLine("## Sobre o usuário com quem você está falando")
        appendLine("- Contexto: conversa privada (DM), sem cargos ou permissões de servidor")
    }.trimEnd()

    private fun renderPermissions(member: Member): String {
        val flags = MODERATION_PERMISSIONS.filter { member.hasPermission(it.first) }.map { it.second }
        return if (flags.isEmpty()) "nenhuma" else flags.joinToString(", ")
    }

    private fun renderMemory(memoria: String?, name: String): String? {
        val texto = memoria?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            appendLine("## O que você lembra sobre $name")
            appendLine(texto)
        }.trimEnd()
    }

    companion object {
        private val MODERATION_PERMISSIONS = listOf(
            Permission.ADMINISTRATOR to "administrator",
            Permission.KICK_MEMBERS to "kickMembers",
            Permission.BAN_MEMBERS to "banMembers",
            Permission.MODERATE_MEMBERS to "moderateMembers",
            Permission.MESSAGE_MANAGE to "manageMessages",
            Permission.MANAGE_CHANNEL to "manageChannel",
            Permission.MANAGE_ROLES to "manageRoles",
            Permission.MANAGE_SERVER to "manageGuild",
        )
    }
}

/**
 * Resultado de [UsuarioService.resolveForEvent]: o nome ao vivo (para substituir `{nome}`) e o
 * bloco de sistema montado.
 */
data class ResolvedUsuario(
    val effectiveName: String,
    val systemPrompt: String,
)
