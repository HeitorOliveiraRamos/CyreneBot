package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * `/banir membro: [motivo:] [apagar_dias:]` — permanent ban, optionally purging the target's
 * recent messages.
 *
 * Requires **Banir Membros**, enforced by Discord (see [ModerationGuards.modOnly]).
 */
@Component
class BanirCommand : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "banir"

    override val definition: CommandData =
        Commands.slash(name, "Bane um membro do servidor")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.BAN_MEMBERS))
            .setGuildOnly(true)
            .addOption(OptionType.USER, "membro", "Quem banir", true)
            .addOption(OptionType.STRING, "motivo", "Motivo registrado no audit log", false)
            .addOption(OptionType.INTEGER, "apagar_dias", "Dias de mensagens recentes a apagar (0 a 7)", false)

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val target = event.getOption("membro")?.asMember
            ?: return event.replyEphemeral("Não achei essa pessoa aqui no servidor 😔")
        val reason = event.getOption("motivo")?.asString?.take(500) ?: "Sem motivo informado"
        val deleteDays = event.getOption("apagar_dias")?.asInt ?: 0

        if (deleteDays !in 0..7) {
            return event.replyEphemeral("Os dias de mensagens precisam estar entre 0 e 7.")
        }
        ModerationGuards.checkTarget(event, target, Permission.BAN_MEMBERS)
            ?.let { return event.replyEphemeral(it) }

        event.deferReply().queue()
        guild.ban(target, deleteDays, TimeUnit.DAYS).reason(reason).queue(
            {
                log.info("AUDIT banir caller={} target={} guild={}", event.user.id, target.id, guild.id)
                event.hook.sendMessage("${target.asMention} foi banido do servidor.\n> $reason").queue()
            },
            { error ->
                log.error("banir falhou para target={} guild={}", target.id, guild.id, error)
                event.hook.sendMessage("O Discord recusou: ${error.message}").setEphemeral(true).queue()
            },
        )
    }
}
