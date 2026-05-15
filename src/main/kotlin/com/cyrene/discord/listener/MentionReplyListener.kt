package com.cyrene.discord.listener

import com.cyrene.ai.OllamaAiService
import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.ConversationService
import com.cyrene.conversation.MentionContextService
import com.cyrene.conversation.MessageRole
import com.cyrene.conversation.UserInfoService
import com.cyrene.discord.tools.DiscordToolContext
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
 *
 * Stateless prompting: each mention is answered without prior @-mention history. Instead,
 * a cached per-(user, guild) profile (effective name, highest role, permission flags,
 * personality summary) is injected as a system block via [UserInfoService]. The exchange
 * is still persisted afterwards so the personality summary can be refreshed periodically.
 *
 * Moderation tool authority is NOT trusted from the cached flags — `DiscordTools.executeMod`
 * still re-verifies caller permissions live against JDA before acting.
 */
@Component
class MentionReplyListener(
    private val ai: OllamaAiService,
    private val sender: DiscordMessageSender,
    private val conversations: ConversationService,
    private val mentionContext: MentionContextService,
    private val userInfoService: UserInfoService,
    private val properties: BotProperties,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cooldowns = mutableMapOf<String, Long>()

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        if (conversations.isInActiveSession(event.author.id, event.channel.id)) return
//        if (event.channel.id != "1377827826903941231") return

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
        if (referenced != null && referenced.author.isBot && referenced.author.id != selfUser.id) {
            // Replying to a different bot — ignore.
            return
        }

        val toolContext = DiscordToolContext(
            callerUserId = event.author.id,
            guildId = if (event.isFromGuild) event.guild.id else null,
            channelId = event.channel.id,
        )

        // Build the history fed to the LLM as a SINGLE user turn — no prior @-mention
        // context. The cached UserInfo profile carries the persistent "who is this" data
        // that the brain previously had to fish out via tool calls.
        val history = buildList {
            if (referenced != null && !referenced.author.isBot) {
                add(
                    ConversationMessage(
                        conversationId = 0L,
                        role = MessageRole.USER,
                        content = "[em resposta a ${referenced.author.effectiveName}: " +
                            "\"${referenced.contentRaw.take(500)}\"]",
                    )
                )
            }
            add(
                ConversationMessage(
                    conversationId = 0L,
                    role = MessageRole.USER,
                    content = withoutMention,
                )
            )
        }

        CompletableFuture
            .supplyAsync(
                {
                    // Resolve / create the cached user profile on the AI executor so the
                    // baseline summarizer call doesn't block the gateway thread.
                    val resolved = userInfoService.resolveForEvent(event)
                    val systemPrompt = resolved?.let { userInfoService.assembleSystemPrompt(it.info) }
                    val reply = ai.chatBrainAndVoice(
                        history = history,
                        toolContext = toolContext,
                        extraSystemPrompt = systemPrompt,
                    )
                    PreparedReply(reply, resolved?.guildId)
                },
                executor,
            )
            .thenAccept { prepared ->
                try {
                    mentionContext.recordExchange(
                        userId = event.author.id,
                        guildId = if (event.isFromGuild) event.guild.id else null,
                        channelId = event.channel.id,
                        userMessage = withoutMention,
                        assistantReply = prepared.reply,
                    )
                    prepared.guildId?.let { userInfoService.incrementExchanges(event.author.id, it) }
                } catch (e: Exception) {
                    log.warn("Failed to persist mention exchange for user {}", event.author.id, e)
                }
                sender.replyLong(event.message, prepared.reply)
            }
            .exceptionally { ex ->
                log.error("MentionReplyListener failed", ex)
                event.message.reply(
                    "Desculpe, ocorreu um erro inesperado ao processar sua solicitação."
                ).queue()
                null
            }
    }

    private data class PreparedReply(val reply: String, val guildId: String?)
}
