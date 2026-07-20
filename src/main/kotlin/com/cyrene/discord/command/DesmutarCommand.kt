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
 * `/desmutar membro:` — lifts an active timeout.
 *
 * Requires **Moderar Membros**, enforced by Discord (see [ModerationGuards.modOnly]).
 */
@Component
class DesmutarCommand : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "desmutar"

    override val definition: CommandData =
        Commands.slash(name, "Remove o silenciamento de um membro")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.MODERATE_MEMBERS))
            .setGuildOnly(true)
            .addOption(OptionType.USER, "membro", "Quem liberar", true)

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val target = event.getOption("membro")?.asMember
            ?: return event.replyEphemeral("Não achei essa pessoa aqui no servidor 😔")

        ModerationGuards.checkTarget(event, target, Permission.MODERATE_MEMBERS)
            ?.let { return event.replyEphemeral(it) }

        if (!target.isTimedOut) {
            return event.replyEphemeral("${target.effectiveName} não está silenciada 🤔")
        }

        event.deferReply().queue()
        guild.removeTimeout(target).reason("Liberado por ${event.user.name}").queue(
            {
                log.info("AUDIT desmutar caller={} target={} guild={}", event.user.id, target.id, guild.id)
                event.hook.sendMessage("Pronto, ${target.asMention} está livre pra falar de novo 💜").queue()
            },
            { error ->
                log.error("desmutar falhou para target={} guild={}", target.id, guild.id, error)
                event.hook.sendMessage("O Discord recusou: ${error.message}").setEphemeral(true).queue()
            },
        )
    }
}
