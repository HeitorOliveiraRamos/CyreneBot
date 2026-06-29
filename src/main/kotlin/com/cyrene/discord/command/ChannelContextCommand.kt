package com.cyrene.discord.command

import com.cyrene.ai.OllamaAiService
import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.MessageRole
import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Component
class ChannelContextCommand(
    private val ai: OllamaAiService,
    private val properties: BotProperties,
    private val executor: Executor,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "contexto-do-canal"

    override val definition: CommandData =
        Commands.slash(name, "Verifica o contexto atual do canal que você está")

    override fun handle(event: SlashCommandInteractionEvent) {
        event.deferReply(true).queue()

        event.channel.history.retrievePast(properties.context.historyFetchSize).queue(
            { recent ->
                val history = recent.reversed().map { msg ->
                    ConversationMessage(
                        conversationId = 0L,
                        role = MessageRole.USER,
                        content = "${msg.author.name}${if (msg.author.isBot) "Bot" else ""}: ${msg.contentRaw}",
                    )
                }
                val prompt = "Based on the previous message history, provide a summary of " +
                    "the conversation's context in high detail, explaining what author had " +
                    "in it's mind. The summary should highlight the main topics discussed " +
                    "and the participants involved. Never break your personality character."

                val withPrompt = history + ConversationMessage(
                    conversationId = 0L,
                    role = MessageRole.USER,
                    content = prompt,
                )
                CompletableFuture
                    .supplyAsync({ ai.chat(withPrompt) }, executor)
                    .thenAccept { summary -> event.hook.sendMessage(summary).queue() }
                    .exceptionally { ex ->
                        log.error("Failed to summarize channel context", ex)
                        event.hook.sendMessage(BotMessages.ERROR).queue()
                        null
                    }
            },
            { error ->
                log.error("Failed to fetch channel history", error)
                event.hook.sendMessage(
                    "Não foi possível recuperar o histórico de mensagens para resumir o contexto."
                ).queue()
            },
        )
    }
}
