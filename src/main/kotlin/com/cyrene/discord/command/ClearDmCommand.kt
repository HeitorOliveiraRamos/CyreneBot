package com.cyrene.discord.command

import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.springframework.stereotype.Component

@Component
class ClearDmCommand : SlashCommand {

    override val name = "limpar"

    override val definition: CommandData =
        Commands.slash(name, "Limpa as mensagens do bot no canal privado")
            .addOption(OptionType.INTEGER, "quantidade", "Quantidade de mensagens a excluir", true)

    override fun handle(event: SlashCommandInteractionEvent) {
        if (event.user.isBot) return
        if (event.isFromGuild) {
            event.reply("Este comando só pode ser usado em conversar privadas!")
                .setEphemeral(true).queue()
            return
        }

        val amount = event.getOption("quantidade")?.asInt ?: 0
        if (amount < 1 || amount > 100) {
            event.reply("Informe um número entre 1 e 100.").setEphemeral(true).queue()
            return
        }

        val channel = event.channel as PrivateChannel
        event.deferReply(true).queue()

        channel.history.retrievePast(amount).queue { messages ->
            var deleted = 0
            messages.forEach { msg ->
                if (msg.author.isBot) {
                    msg.delete().queue()
                    deleted++
                }
            }
            event.hook.sendMessage("✅ $deleted mensagens do bot foram apagadas.")
                .setEphemeral(true).queue()
        }
    }
}
