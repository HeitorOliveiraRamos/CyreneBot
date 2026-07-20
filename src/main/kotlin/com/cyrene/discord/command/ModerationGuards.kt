package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions

/**
 * Shared pre-flight for the moderation slash commands.
 *
 * Who may RUN a command is not decided here — it's declared on the command definition via
 * [modOnly] and enforced by Discord before the interaction ever reaches the bot, which is
 * both stricter than an in-process check (the command is hidden from users who lack the
 * permission) and impossible to talk the bot out of.
 *
 * What Discord does NOT check is everything below: that the bot itself can act on the
 * target, and that the invoker outranks them. Without the caller-hierarchy check any
 * moderator could use the bot to ban someone above them — laundering an action their own
 * role forbids. That one is the reason this file exists.
 */
object ModerationGuards {

    /**
     * Declares a command runnable only by members holding [permission]. Discord hides it
     * from everyone else and rejects the interaction server-side, so the handler can assume
     * the caller is authorized.
     */
    fun modOnly(permission: Permission): DefaultMemberPermissions =
        DefaultMemberPermissions.enabledFor(permission)

    /**
     * Validates that [event]'s caller and the bot may both act on [target], returning the
     * user-facing refusal or null when the action may proceed.
     *
     * [botPermission] is re-checked live because a server can revoke the bot's role
     * permission at any time; the failure is then a clear message instead of an opaque JDA
     * exception. Pass null for actions that touch no Discord state (`/avisar` only writes a
     * row) — the caller-hierarchy rule still applies, so a warning can't be aimed upward.
     */
    fun checkTarget(
        event: SlashCommandInteractionEvent,
        target: Member,
        botPermission: Permission?,
    ): String? {
        val guild = event.guild ?: return BotMessages.GUILD_ONLY
        val self = guild.selfMember
        val caller = event.member ?: return "Não consegui te identificar neste servidor 😔"

        return when {
            target.id == self.id -> "Não vou fazer isso comigo mesma 😌"
            target.id == caller.id -> "Isso é com você mesma? Melhor não 😅"
            target.isOwner -> "Não dá — essa pessoa é a dona do servidor."
            botPermission != null && !self.hasPermission(botPermission) ->
                "Eu não tenho a permissão **${botPermission.getName()}** neste servidor 😔"
            botPermission != null && !self.canInteract(target) ->
                "O cargo dessa pessoa está acima do meu, então eu não consigo agir nela."
            !caller.canInteract(target) ->
                "Você não está acima dessa pessoa na hierarquia de cargos — não posso fazer isso por você."
            else -> null
        }
    }

    /**
     * Validates a Discord snowflake: a numeric id of plausible length. Needed by `/desbanir`,
     * the one command whose target can't be a user picker — a banned user is not a member, so
     * Discord has nobody to offer and the id arrives as free text.
     */
    fun isSnowflake(s: String): Boolean =
        s.isNotEmpty() && s.length in 5..20 && s.all(Char::isDigit)

    /**
     * Role-specific guards for `/cargo`. `@everyone` and integration-managed roles cannot be
     * assigned at all, and both the bot and the caller must sit above the role — same
     * anti-laundering reasoning as [checkTarget].
     */
    fun checkRole(event: SlashCommandInteractionEvent, role: Role): String? {
        val guild = event.guild ?: return BotMessages.GUILD_ONLY
        val caller = event.member ?: return "Não consegui te identificar neste servidor 😔"
        return when {
            role.isPublicRole -> "O cargo `@everyone` não dá pra mexer 😌"
            role.isManaged -> "O cargo **${role.name}** é gerenciado por uma integração — ninguém atribui ele na mão."
            !guild.selfMember.canInteract(role) -> "O cargo **${role.name}** está acima do meu, não alcanço."
            !caller.canInteract(role) -> "O cargo **${role.name}** está acima do seu — não posso fazer isso por você."
            else -> null
        }
    }
}
