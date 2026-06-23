package com.cyrene.discord.listener

import com.cyrene.ai.OllamaAiService
import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.ConversationService
import com.cyrene.conversation.MessageRole
import com.cyrene.conversation.UsuarioService
import com.cyrene.discord.tools.DiscordToolContext
import com.cyrene.discord.util.DiscordMessageSender
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Forwards messages from users with an active `/iniciar-conversa` session to Ollama,
 * persisting both sides of the exchange as a single combined row.
 *
 * Like the @-mention path, this listener injects the user block resolved by
 * [UsuarioService] as a system block so the voice model knows the user's name (avoiding
 * leaked `{nome}` placeholders from the persona examples) and the brain sees the caller's
 * live role/permissions plus whatever the user asked the bot to remember.
 *
 * The user turn is no longer persisted before the AI call — exchanges are written as a
 * single combined row after the reply lands. A crash mid-call therefore loses the
 * in-flight user message; chat sessions are short-lived enough that this trade-off
 * (chosen explicitly) is acceptable in exchange for a simpler persistence shape.
 */
@Component
class ChatSessionListener(
    private val conversations: ConversationService,
    private val ai: OllamaAiService,
    private val sender: DiscordMessageSender,
    private val usuarioService: UsuarioService,
    private val properties: BotProperties,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return

        val message = event.message
        val userId = message.author.id
        val channelId = message.channel.id

        val testChannelId = properties.testChannelId
        if (!testChannelId.isNullOrBlank() && channelId != testChannelId) return

        val active = conversations.activeConversation(userId, channelId) ?: return
        val conversationId = active.id ?: return
        val content = message.contentRaw

        // History is the prior persisted exchanges; the current user turn is appended
        // in-memory before the AI call and persisted atomically afterwards along with
        // the reply.
        val priorHistory = conversations.history(conversationId)
        val historyForAi = priorHistory + ConversationMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = content,
        )

        val toolContext = DiscordToolContext(
            callerUserId = userId,
            guildId = if (event.isFromGuild) event.guild.id else null,
            channelId = channelId,
        )

        CompletableFuture
            .supplyAsync(
                {
                    val resolved = usuarioService.resolveForEvent(event)
                    ai.chatBrainAndVoice(
                        history = historyForAi,
                        toolContext = toolContext,
                        extraSystemPrompt = resolved?.systemPrompt,
                        userName = resolved?.effectiveName,
                    )
                },
                executor,
            )
            .thenAccept { reply ->
                val persisted = conversations.recordExchange(conversationId, content, reply)
                if (!persisted) {
                    log.debug("Skipped recording exchange for conv {} — no longer active", conversationId)
                }
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
