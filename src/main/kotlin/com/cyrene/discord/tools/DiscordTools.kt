package com.cyrene.discord.tools

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Tools the LLM can invoke to inspect the Discord environment and (when authorized) take
 * moderation actions via JDA.
 *
 * Safety model — every destructive tool re-checks authority itself, never trusting the
 * model's narrative:
 *  - The CALLER's user ID comes from [DiscordToolContext], never from tool arguments.
 *  - The caller's permission is verified server-side before the action is attempted.
 *  - The bot's own permission and role-hierarchy position are verified too.
 *  - Targets are always resolved by numeric snowflake; name-based lookup is rejected to
 *    prevent the model from acting on the wrong user when names collide.
 *  - Self-targeting, bot-targeting, and acting on the guild owner are blocked outright.
 *
 * Returned values are plain `Map`s; Spring AI serializes them to JSON for the model.
 */
@Component
class DiscordTools(
    private val jda: JDA,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(
        description = "Retorna informações sobre o usuário que está falando com você (o autor da mensagem): " +
            "ID no Discord, nome de usuário, apelido no servidor, cargos e se é o dono do servidor. " +
            "Use SEMPRE que precisar se dirigir ao usuário pelo nome ou confirmar quem está pedindo a ação. " +
            "Equivalentes em PT-BR: 'quem sou eu', 'quem está falando', 'meu cargo', 'meus papéis', 'minhas roles'.",
    )
    fun getCallerInfo(toolContext: ToolContext): Map<String, Any?> {
        val ctx = ctx(toolContext)
        val guild = guildOrNull(ctx) ?: return mapOf(
            "callerUserId" to ctx.callerUserId,
            "channelId" to ctx.channelId,
            "context" to "direct_message",
            "note" to "Caller is in a direct message; no guild-scoped data is available.",
        )
        val member = retrieveMember(guild, ctx.callerUserId)
            ?: return mapOf("error" to "Caller ${ctx.callerUserId} not found in guild ${guild.id}")
        return memberSummary(member, guild)
    }

    @Tool(
        description = "Retorna informações sobre o servidor (guild) onde a conversa acontece: nome, ID, " +
            "número de membros, ID do dono e o apelido do bot no servidor. " +
            "Use quando o usuário pedir: 'info do servidor', 'qual servidor é esse', 'quantos membros', " +
            "'quem é o dono', 'este servidor', 'deste servidor'.",
    )
    fun getGuildInfo(toolContext: ToolContext): Map<String, Any?> {
        val guild = guildOrError(toolContext) ?: return dmError()
        return mapOf(
            "guildId" to guild.id,
            "guildName" to guild.name,
            "memberCount" to guild.memberCount,
            "ownerId" to guild.ownerId,
            "channelCount" to guild.channels.size,
            "roleCount" to guild.roles.size,
            "myDisplayName" to guild.selfMember.effectiveName,
            "myUserId" to guild.selfMember.id,
        )
    }

    @Tool(
        description = "Lista as permissões de moderação que O BOT possui neste servidor. " +
            "Chame antes de tentar moderar para avisar o usuário caso o bot não tenha permissão. " +
            "Equivalentes em PT-BR: 'suas permissões', 'o que você pode fazer', 'permissões do bot'.",
    )
    fun getMyModerationPermissions(toolContext: ToolContext): Map<String, Any?> {
        val guild = guildOrError(toolContext) ?: return dmError()
        return permissionFlags(guild.selfMember)
    }

    @Tool(
        description = "Lista as permissões de moderação que O USUÁRIO (quem está falando com você) tem neste " +
            "servidor. SEMPRE chame antes de tentar qualquer ação de moderação a pedido dele; recuse a ação " +
            "se a permissão correspondente for false. " +
            "Equivalentes em PT-BR: 'minhas permissões', 'o que eu posso fazer', 'sou admin', 'posso banir'.",
    )
    fun getCallerModerationPermissions(toolContext: ToolContext): Map<String, Any?> {
        val ctx = ctx(toolContext)
        val guild = guildOrError(toolContext) ?: return dmError()
        val member = retrieveMember(guild, ctx.callerUserId)
            ?: return mapOf("error" to "Caller not found in guild")
        return permissionFlags(member)
    }

    @Tool(
        description = "Procura um membro do servidor pelo ID numérico do Discord (snowflake). Retorna " +
            "nome de usuário, apelido, cargos e data de entrada. Use para confirmar que o alvo existe e " +
            "é a pessoa certa ANTES de qualquer ação de moderação.",
    )
    fun lookupMember(
        @ToolParam(description = "ID numérico do usuário no Discord, ex.: 123456789012345678")
        userId: String,
        toolContext: ToolContext,
    ): Map<String, Any?> {
        val guild = guildOrError(toolContext) ?: return dmError()
        if (!isSnowflake(userId)) return mapOf("error" to "userId must be a numeric Discord snowflake")
        val member = retrieveMember(guild, userId)
            ?: return mapOf("error" to "No member with id $userId in guild ${guild.id}")
        return memberSummary(member, guild)
    }

    @Tool(
        description = "Aplica TIMEOUT (silenciamento temporário) em um membro do servidor por uma " +
            "quantidade de minutos. ESTA é a ação correta sempre que o usuário pedir para: " +
            "'mutar', 'muta', 'silenciar', 'silencia', 'calar', 'calar a boca', 'dar um tempo', " +
            "'castigar', 'pôr no castigo', 'tirar do ar temporariamente', 'mute', 'timeout'. " +
            "EXIGE que o solicitante tenha a permissão 'Moderar Membros'. Alvo deve ser ID numérico. " +
            "Prefira esta ação a kick ou ban para infrações leves ou primárias. " +
            "IMPORTANTE: se o solicitante NÃO informou a duração, NÃO chame outra ação no lugar — " +
            "responda pedindo a duração em minutos.",
    )
    fun timeoutMember(
        @ToolParam(description = "ID numérico do usuário alvo no Discord")
        userId: String,
        @ToolParam(description = "Duração do timeout em minutos (1 a 10080, ou seja, até 7 dias)")
        minutes: Int,
        @ToolParam(description = "Motivo registrado no audit log do Discord")
        reason: String,
        toolContext: ToolContext,
    ): Map<String, Any?> = executeMod(toolContext, userId, Permission.MODERATE_MEMBERS, "timeout") { guild, target ->
        if (minutes !in 1..10080) {
            return@executeMod mapOf("ok" to false, "error" to "minutes must be between 1 and 10080 (7 days max)")
        }
        guild.timeoutFor(target, Duration.ofMinutes(minutes.toLong()))
            .reason(reason.take(500))
            .complete()
        mapOf("ok" to true, "action" to "timeout", "targetId" to target.id, "minutes" to minutes)
    }

    @Tool(
        description = "Remove um timeout/silenciamento ativo de um membro do servidor. " +
            "Use quando o usuário pedir: 'desmuta', 'desmutar', 'tira o mute', 'tira o castigo', " +
            "'remover timeout', 'libera o fulano', 'destira o silêncio'. " +
            "Exige a permissão 'Moderar Membros'.",
    )
    fun untimeoutMember(
        @ToolParam(description = "ID numérico do usuário alvo no Discord")
        userId: String,
        toolContext: ToolContext,
    ): Map<String, Any?> = executeMod(toolContext, userId, Permission.MODERATE_MEMBERS, "untimeout") { guild, target ->
        guild.removeTimeout(target).reason("Timeout removed via bot tool call").complete()
        mapOf("ok" to true, "action" to "untimeout", "targetId" to target.id)
    }

    @Tool(
        description = "EXPULSA (kick) um membro do servidor. O membro pode voltar se tiver um convite. " +
            "Use APENAS quando o usuário pedir explicitamente para: 'expulsar', 'expulsa', 'chutar', " +
            "'chuta', 'tirar do servidor', 'remover do servidor', 'mandar embora', 'kick'. " +
            "NÃO use para pedidos de 'mutar', 'silenciar', 'calar' — para esses use timeoutMember. " +
            "EXIGE a permissão 'Expulsar Membros' do solicitante. O bot também precisa da permissão e " +
            "estar acima do alvo na hierarquia de cargos.",
    )
    fun kickMember(
        @ToolParam(description = "ID numérico do usuário alvo no Discord")
        userId: String,
        @ToolParam(description = "Motivo registrado no audit log do Discord")
        reason: String,
        toolContext: ToolContext,
    ): Map<String, Any?> = executeMod(toolContext, userId, Permission.KICK_MEMBERS, "kick") { guild, target ->
        guild.kick(target).reason(reason.take(500)).complete()
        mapOf("ok" to true, "action" to "kick", "targetId" to target.id)
    }

    @Tool(
        description = "BANE um membro do servidor permanentemente. DESTRUTIVO — ele não pode voltar até " +
            "ser desbanido. Use quando o usuário pedir explicitamente para: 'banir', 'bane', 'banimento', " +
            "'ban'. NÃO use para 'mutar', 'silenciar', 'expulsar', 'chutar' — para esses use timeoutMember " +
            "ou kickMember. EXIGE a permissão 'Banir Membros'. Só chame quando o solicitante deu um motivo " +
            "claro e grave; caso contrário, prefira timeout ou kick.",
    )
    fun banMember(
        @ToolParam(description = "ID numérico do usuário alvo no Discord")
        userId: String,
        @ToolParam(description = "Motivo registrado no audit log do Discord")
        reason: String,
        @ToolParam(description = "Quantos dias de mensagens recentes do usuário deletar (0 a 7).")
        deleteMessageDays: Int,
        toolContext: ToolContext,
    ): Map<String, Any?> = executeMod(toolContext, userId, Permission.BAN_MEMBERS, "ban") { guild, target ->
        if (deleteMessageDays !in 0..7) {
            return@executeMod mapOf("ok" to false, "error" to "deleteMessageDays must be between 0 and 7")
        }
        guild.ban(target, deleteMessageDays, TimeUnit.DAYS).reason(reason.take(500)).complete()
        mapOf("ok" to true, "action" to "ban", "targetId" to target.id, "deleteDays" to deleteMessageDays)
    }

    // -------------------- internals -------------------- //

    /**
     * Common pre-flight for every destructive moderation tool. Validates the snowflake,
     * confirms caller and bot both hold [permission], blocks self/bot/owner targets, and
     * checks the role-hierarchy interaction guard. The lambda runs only after every check
     * passes; JDA exceptions inside it are caught and surfaced to the model as a structured
     * error so it can explain the failure rather than retry blindly.
     */
    private fun executeMod(
        toolContext: ToolContext,
        targetId: String,
        permission: Permission,
        actionLabel: String,
        body: (Guild, Member) -> Map<String, Any?>,
    ): Map<String, Any?> {
        val ctx = ctx(toolContext)
        val guild = guildOrError(toolContext) ?: return dmError()
        if (!isSnowflake(targetId)) {
            return mapOf("ok" to false, "error" to "targetId must be a numeric Discord snowflake")
        }

        val caller = retrieveMember(guild, ctx.callerUserId)
            ?: return mapOf("ok" to false, "error" to "Caller not found in guild")

        if (!caller.hasPermission(permission)) {
            log.warn(
                "Tool {} denied: caller {} lacks {} in guild {}",
                actionLabel, caller.id, permission, guild.id,
            )
            return mapOf(
                "ok" to false,
                "error" to "Caller does not have the '${permission.getName()}' permission.",
            )
        }

        val self = guild.selfMember
        if (!self.hasPermission(permission)) {
            return mapOf(
                "ok" to false,
                "error" to "Bot lacks the '${permission.getName()}' permission in this server.",
            )
        }

        val target = retrieveMember(guild, targetId)
            ?: return mapOf("ok" to false, "error" to "Target $targetId is not in this guild")

        if (target.id == self.id) {
            return mapOf("ok" to false, "error" to "Refusing to act on the bot itself.")
        }
        if (target.id == caller.id) {
            return mapOf("ok" to false, "error" to "Refusing to act on the caller themselves.")
        }
        if (target.isOwner) {
            return mapOf("ok" to false, "error" to "Cannot act on the server owner.")
        }
        if (!self.canInteract(target)) {
            return mapOf("ok" to false, "error" to "Bot's role is not high enough to act on the target.")
        }
        if (!caller.canInteract(target)) {
            return mapOf("ok" to false, "error" to "Caller's role is not high enough to act on the target.")
        }

        return try {
            log.info(
                "AUDIT tool={} caller={} target={} guild={}",
                actionLabel, caller.id, target.id, guild.id,
            )
            body(guild, target)
        } catch (e: Exception) {
            log.error(
                "Tool {} failed for caller={} target={} guild={}",
                actionLabel, caller.id, target.id, guild.id, e,
            )
            mapOf("ok" to false, "error" to "Discord API rejected the request: ${e.message}")
        }
    }

    private fun ctx(toolContext: ToolContext): DiscordToolContext {
        val raw = toolContext.context[DiscordToolContext.KEY]
        require(raw is DiscordToolContext) {
            "DiscordToolContext missing from ToolContext under key '${DiscordToolContext.KEY}'"
        }
        return raw
    }

    private fun guildOrNull(ctx: DiscordToolContext): Guild? =
        ctx.guildId?.let { jda.getGuildById(it) }

    private fun guildOrError(toolContext: ToolContext): Guild? = guildOrNull(ctx(toolContext))

    private fun dmError(): Map<String, Any?> = mapOf(
        "error" to "This action requires a server context, but the conversation is in a direct message.",
    )

    /**
     * Synchronous member lookup — necessary because the LLM call site is already on the
     * AI executor pool, and we need the member resolved before returning a value to the
     * model. Tries the JDA member cache first (no I/O, no thread block); only falls back
     * to a blocking REST `retrieveMemberById().complete()` on cache miss. With the
     * `GUILD_MEMBERS` intent enabled in [com.cyrene.discord.JdaConfig], the cache is
     * populated on guild ready, so the hot path stays non-blocking. Returns null on any
     * failure so the caller maps it to a clean "not found" response.
     */
    private fun retrieveMember(guild: Guild, userId: String): Member? {
        guild.getMemberById(userId)?.let { return it }
        return try {
            guild.retrieveMemberById(userId).complete()
        } catch (e: Exception) {
            log.debug("retrieveMember({}, {}) failed: {}", guild.id, userId, e.message)
            null
        }
    }

    private fun memberSummary(member: Member, guild: Guild): Map<String, Any?> = mapOf(
        "userId" to member.id,
        "username" to member.user.name,
        "displayName" to member.effectiveName,
        "isBot" to member.user.isBot,
        "isOwner" to member.isOwner,
        "joinedAt" to member.timeJoined.toString(),
        "roles" to member.roles.map { mapOf("id" to it.id, "name" to it.name) },
        "guildId" to guild.id,
    )

    private fun permissionFlags(member: Member): Map<String, Any?> = mapOf(
        "userId" to member.id,
        "displayName" to member.effectiveName,
        "administrator" to member.hasPermission(Permission.ADMINISTRATOR),
        "kickMembers" to member.hasPermission(Permission.KICK_MEMBERS),
        "banMembers" to member.hasPermission(Permission.BAN_MEMBERS),
        "moderateMembers" to member.hasPermission(Permission.MODERATE_MEMBERS),
        "manageMessages" to member.hasPermission(Permission.MESSAGE_MANAGE),
        "manageChannel" to member.hasPermission(Permission.MANAGE_CHANNEL),
        "manageGuild" to member.hasPermission(Permission.MANAGE_SERVER),
    )

    internal companion object {
        /**
         * Validates a Discord snowflake: a non-empty numeric id of plausible length. Targets
         * are accepted only as snowflakes (never by name) so the model can't act on the wrong
         * user when display names collide. Companion-scoped so this safety check is
         * unit-testable without a live JDA.
         */
        internal fun isSnowflake(s: String): Boolean =
            s.isNotEmpty() && s.length in 5..20 && s.all(Char::isDigit)
    }
}
