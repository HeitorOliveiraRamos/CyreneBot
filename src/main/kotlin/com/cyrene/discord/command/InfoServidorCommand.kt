package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/**
 * `/info-servidor` — read-only summary of the guild.
 *
 * Deliberately ungated: it reveals nothing a member can't already see in the server's own UI,
 * so requiring a moderation permission would only make it useless to the people who ask for it.
 */
@Component
class InfoServidorCommand : SlashCommand {

    override val name = "info-servidor"

    override val definition: CommandData =
        Commands.slash(name, "Mostra as informações deste servidor")
            .setGuildOnly(true)

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)

        val criado = guild.timeCreated.format(DATE)
        val texto = buildString {
            appendLine("## ${guild.name}")
            appendLine("- **Membros:** ${guild.memberCount}")
            appendLine("- **Dono:** <@${guild.ownerId}>")
            appendLine("- **Canais:** ${guild.channels.size}")
            appendLine("- **Cargos:** ${guild.roles.size}")
            appendLine("- **Nível de impulso:** ${guild.boostTier.key} (${guild.boostCount} impulsos)")
            appendLine("- **Criado em:** $criado")
            append("-# ID: ${guild.id}")
        }
        event.reply(texto).queue()
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
