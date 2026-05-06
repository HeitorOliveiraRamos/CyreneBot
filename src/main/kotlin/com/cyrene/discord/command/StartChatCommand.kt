package com.cyrene.discord.command

import com.cyrene.conversation.ConversationService
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.springframework.stereotype.Component

@Component
class StartChatCommand(private val conversations: ConversationService) : SlashCommand {

    override val name = "iniciar-conversa"

    override val definition: CommandData =
        Commands.slash(name, "Inicia uma sessão de chat com o bot.")

    override fun handle(event: SlashCommandInteractionEvent) {
        val userId = event.user.id
        val channelId = event.channel.id
        val openingLine = "Olá! Sobre o que iremos conversar hoje?"

        val started = conversations.startSession(userId, channelId, openingLine)
        if (started == null) {
            event.reply(
                "Você já está em uma sessão de conversa neste canal. " +
                    "Use `/encerrar-conversa` para terminar a sessão atual primeiro."
            ).setEphemeral(true).queue()
            return
        }
        event.reply(openingLine).queue()
    }
}
