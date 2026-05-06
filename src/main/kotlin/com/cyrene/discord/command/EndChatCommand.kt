package com.cyrene.discord.command

import com.cyrene.conversation.ConversationService
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.springframework.stereotype.Component

@Component
class EndChatCommand(private val conversations: ConversationService) : SlashCommand {

    override val name = "encerrar-conversa"

    override val definition: CommandData =
        Commands.slash(name, "Encerra a sessão de chat atual com o bot.")

    override fun handle(event: SlashCommandInteractionEvent) {
        val ended = conversations.endSession(event.user.id, event.channel.id)
        if (!ended) {
            event.reply("Você não está em uma sessão de conversa ativa neste canal.")
                .setEphemeral(true)
                .queue()
            return
        }
        event.reply("Até mais!").queue()
    }
}
