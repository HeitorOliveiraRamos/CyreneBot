package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import com.cyrene.moderation.Aviso
import com.cyrene.moderation.AvisoRepository
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `/avisar membro: motivo:` — records a warning and states the running count.
 *
 * The count is the point. A warning nobody can tally is just a message in the chat that
 * scrolls away; showing "3º aviso" is what lets a moderator see a pattern before reaching for
 * `/mutar` or `/banir`. Read the history back with `/avisos`.
 *
 * Touches no Discord state, so [ModerationGuards.checkTarget] is called with a null bot
 * permission — but the caller-hierarchy rule still applies, so warnings can't be aimed upward.
 *
 * Requires **Moderar Membros**, enforced by Discord (see [ModerationGuards.modOnly]).
 */
@Component
class AvisarCommand(
    private val avisos: AvisoRepository,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "avisar"

    override val definition: CommandData =
        Commands.slash(name, "Registra uma advertência para um membro")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.MODERATE_MEMBERS))
            .setGuildOnly(true)
            .addOption(OptionType.USER, "membro", "Quem avisar", true)
            .addOption(OptionType.STRING, "motivo", "O que aconteceu", true)

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val target = event.getOption("membro")?.asMember
            ?: return event.replyEphemeral("Não achei essa pessoa aqui no servidor 😔")
        val motivo = event.getOption("motivo")!!.asString.trim().take(1000)

        if (motivo.isEmpty()) {
            return event.replyEphemeral("Um aviso sem motivo não serve pra nada depois — escreve o que rolou 💜")
        }
        ModerationGuards.checkTarget(event, target, botPermission = null)
            ?.let { return event.replyEphemeral(it) }

        event.deferReply().queue()
        val total = try {
            avisos.save(
                Aviso(
                    guildId = guild.id,
                    usuarioId = target.id,
                    moderadorId = event.user.id,
                    motivo = motivo,
                ),
            )
            avisos.countByGuildIdAndUsuarioId(guild.id, target.id)
        } catch (e: Exception) {
            // Never claim a warning was recorded when it wasn't — the count would lie later.
            log.error("avisar falhou ao gravar target={} guild={}", target.id, guild.id, e)
            event.hook.sendMessage("Não consegui registrar o aviso agora 😔 Tenta de novo daqui a pouco?")
                .setEphemeral(true).queue()
            return
        }

        log.info("AUDIT avisar caller={} target={} guild={} total={}", event.user.id, target.id, guild.id, total)
        event.hook.sendMessage(
            "${target.asMention} recebeu um aviso — **$total no total** neste servidor.\n> $motivo",
        ).queue()
    }
}
