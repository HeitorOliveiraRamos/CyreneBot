package com.cyrene.discord.listener

import com.cyrene.ai.OllamaAiService
import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationService
import com.cyrene.discord.util.DiscordMessageSender
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Replies when the bot is @-mentioned in any channel. Skipped when the mentioning user
 * is already in an active `/iniciar-conversa` session (the chat listener handles those).
 * Replaces the legacy `ReplyAI` listener.
 */
@Component
class MentionReplyListener(
    private val ai: OllamaAiService,
    private val sender: DiscordMessageSender,
    private val conversations: ConversationService,
    private val properties: BotProperties,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cooldowns = mutableMapOf<String, Long>()

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        if (conversations.isInActiveSession(event.author.id, event.channel.id)) return

        val selfUser = event.jda.selfUser
        if (selfUser !in event.message.mentions.users) return

        val now = System.currentTimeMillis()
        val userId = event.author.id
        val cooldownSeconds = properties.reply.cooldownSeconds
        val secondsSince = TimeUnit.MILLISECONDS.toSeconds(now - (cooldowns[userId] ?: 0L))
        if (secondsSince < cooldownSeconds) {
            val wait = cooldownSeconds - secondsSince
            event.message.reply(
                "Você precisa esperar $wait segundos antes de fazer outra pergunta."
            ).queue()
            return
        }
        cooldowns[userId] = now

        val raw = event.message.contentRaw
        val withoutMention = raw.replace("<@${selfUser.id}>", "").trim()
        val referenced = event.message.referencedMessage

        val systemPrompt = if (referenced != null) {
            if (referenced.author.isBot) return
            "The user ${event.author.name} is going to ask you a question and the content " +
                "of the question is: ${referenced.contentRaw}."
        } else {
            "The user you are talking to is called: ${event.author.name}. " +
                "**Important: You must add his name in your response.**"
        }

        CompletableFuture
            .supplyAsync(
                { ai.chatOnce(userMessage = withoutMention, extraSystemPrompt = systemPrompt) },
                executor,
            )
            .thenAccept { reply -> sender.replyLong(event.message, reply) }
            .exceptionally { ex ->
                log.error("MentionReplyListener failed", ex)
                event.message.reply(
                    "Desculpe, ocorreu um erro inesperado ao processar sua solicitação."
                ).queue()
                null
            }
    }
}
