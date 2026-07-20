package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/**
 * `/info-membro [membro:]` — read-only profile of a member; defaults to the caller.
 *
 * Ungated for the same reason as [InfoServidorCommand]: every field here is already visible
 * by clicking someone's name in Discord.
 */
@Component
class InfoMembroCommand : SlashCommand {

    override val name = "info-membro"

    override val definition: CommandData =
        Commands.slash(name, "Mostra as informações de um membro (vazio = você)")
            .setGuildOnly(true)
            .addOption(OptionType.USER, "membro", "De quem. Vazio = você mesma.", false)

    override fun handle(event: SlashCommandInteractionEvent) {
        event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val target = event.getOption("membro")?.asMember ?: event.member
            ?: return event.replyEphemeral("Não achei essa pessoa aqui no servidor 😔")

        // Roles come back highest-first; @everyone is implicit and never worth listing.
        val cargos = target.roles.joinToString(", ") { it.name }.ifEmpty { "nenhum" }
        val texto = buildString {
            appendLine("## ${target.effectiveName}")
            appendLine("- **Usuário:** ${target.user.name} (${target.asMention})")
            appendLine("- **Entrou em:** ${target.timeJoined.format(DATE)}")
            appendLine("- **Conta criada em:** ${target.user.timeCreated.format(DATE)}")
            appendLine("- **Cargos:** $cargos")
            if (target.isOwner) appendLine("- 👑 Dona do servidor")
            if (target.user.isBot) appendLine("- 🤖 É um bot")
            target.timeOutEnd?.let { appendLine("- 🔇 Silenciada até ${it.format(DATE_TIME)}") }
            append("-# ID: ${target.id}")
        }
        event.reply(texto).queue()
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    }
}
