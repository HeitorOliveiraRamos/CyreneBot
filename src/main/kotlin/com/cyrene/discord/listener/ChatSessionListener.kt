package com.cyrene.discord.listener

import com.cyrene.ai.OllamaAiService
import com.cyrene.conversation.ConversationService
import com.cyrene.discord.util.DiscordMessageSender
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Forwards messages from users with an active `/iniciar-conversa` session to Ollama,
 * persisting both sides of the exchange. Replaces the legacy `ChatBot` listener.
 */
@Component
class ChatSessionListener(
    private val conversations: ConversationService,
    private val ai: OllamaAiService,
    private val sender: DiscordMessageSender,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return

        val message = event.message
        val userId = message.author.id
        val channelId = message.channel.id

        val active = conversations.activeConversation(userId, channelId) ?: return
        val conversationId = active.id ?: return
        val content = message.contentRaw

        // Persist the user turn first so it survives a crash mid-call, then ask the AI
        // using the just-updated history (which now ends with this user message).
        conversations.recordUserMessage(conversationId, content)
        val history = conversations.history(conversationId)

        CompletableFuture
            .supplyAsync({ ai.chat(history) }, executor)
            .thenAccept { reply ->
                conversations.recordAssistantMessage(conversationId, reply)
                sender.replyLong(message, reply)
            }
            .exceptionally { ex ->
                log.error("Failed to process chat-session message for conv {}", conversationId, ex)
                message.reply("Desculpe, ocorreu um erro inesperado ao tentar obter uma resposta.")
                    .queue()
                null
            }
    }
}
