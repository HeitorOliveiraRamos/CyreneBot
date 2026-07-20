package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `/expulsar membro: [motivo:]` — kicks a member. Unlike `/banir` they can rejoin with a
 * new invite, which makes this the right default for a first offence.
 *
 * Requires **Expulsar Membros**, enforced by Discord (see [ModerationGuards.modOnly]).
 */
@Component
class ExpulsarCommand : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "expulsar"

    override val definition: CommandData =
        Commands.slash(name, "Expulsa um membro do servidor (ele pode voltar com um convite)")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.KICK_MEMBERS))
            .setGuildOnly(true)
            .addOption(OptionType.USER, "membro", "Quem expulsar", true)
            .addOption(OptionType.STRING, "motivo", "Motivo registrado no audit log", false)

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val target = event.getOption("membro")?.asMember
            ?: return event.replyEphemeral("Não achei essa pessoa aqui no servidor 😔")
        val reason = event.getOption("motivo")?.asString?.take(500) ?: "Sem motivo informado"

        ModerationGuards.checkTarget(event, target, Permission.KICK_MEMBERS)
            ?.let { return event.replyEphemeral(it) }

        event.deferReply().queue()
        guild.kick(target).reason(reason).queue(
            {
                log.info("AUDIT expulsar caller={} target={} guild={}", event.user.id, target.id, guild.id)
                event.hook.sendMessage("${target.asMention} foi expulso do servidor.\n> $reason").queue()
            },
            { error ->
                log.error("expulsar falhou para target={} guild={}", target.id, guild.id, error)
                event.hook.sendMessage("O Discord recusou: ${error.message}").setEphemeral(true).queue()
            },
        )
    }
}
