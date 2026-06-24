package com.cyrene.discord.listener

import com.cyrene.ai.OllamaAiService
import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.ConversationService
import com.cyrene.conversation.MentionContextService
import com.cyrene.conversation.MessageRole
import com.cyrene.conversation.UsuarioService
import com.cyrene.discord.ChainEntry
import com.cyrene.discord.ReplyChainResolver
import com.cyrene.discord.tools.DiscordToolContext
import com.cyrene.discord.util.DiscordMessageSender
import net.dv8tion.jda.api.entities.Message
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
 * the user block resolved by [UsuarioService] (effective name, live highest role and
 * permissions, plus whatever the user asked the bot to remember) is injected as a system
 * block. The exchange is still persisted afterwards for auditability.
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
    private val usuarioService: UsuarioService,
    private val replyChainResolver: ReplyChainResolver,
    private val properties: BotProperties,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cooldowns = mutableMapOf<String, Long>()

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        if (conversations.isInActiveSession(event.author.id, event.channel.id)) return
        val testChannelId = properties.testChannelId
        if (!testChannelId.isNullOrBlank() && event.channel.id != testChannelId) return

        val selfUser = event.jda.selfUser
        // Allow processing for either an explicit mention OR when the incoming
        // message is a reply to the bot's own message (so users can continue a
        // mini-chat by replying without re-mentioning).
        val raw = event.message.contentRaw
        val referenced = event.message.referencedMessage
        val isMention = selfUser in event.message.mentions.users
        val isReplyToSelf = referenced?.author?.id == selfUser.id
        if (!isMention && !isReplyToSelf) return
        if (referenced != null && referenced.author.isBot && referenced.author.id != selfUser.id) {
            // Replying to a different bot — ignore.
            return
        }

        val withoutMention = raw.replace("<@${selfUser.id}>", "").trim()
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

        val toolContext = DiscordToolContext(
            callerUserId = event.author.id,
            guildId = if (event.isFromGuild) event.guild.id else null,
            channelId = event.channel.id,
        )

        CompletableFuture
            .supplyAsync(
                {
                    // Resolve / upsert the user row on the AI executor so the JDA lookups
                    // don't block the gateway thread.
                    val resolved = usuarioService.resolveForEvent(event)

                    // Reconstruct the Discord reply chain (oldest → newest). Bounded by
                    // maxHops + budget so worst-case latency stays predictable; safe to
                    // call here because we're already off the gateway thread.
                    val chain = replyChainResolver.resolveChain(event.message, selfUser.id)
                    val currentName = resolved?.effectiveName ?: event.author.effectiveName
                    val history = buildHistory(chain, referenced, currentName, withoutMention, selfUser.id)

                    ai.chatBrainAndVoice(
                        history = history,
                        toolContext = toolContext,
                        extraSystemPrompt = resolved?.systemPrompt,
                        userName = resolved?.effectiveName,
                    )
                },
                executor,
            )
            .thenAccept { reply ->
                try {
                    mentionContext.recordExchange(
                        userId = event.author.id,
                        guildId = if (event.isFromGuild) event.guild.id else null,
                        channelId = event.channel.id,
                        userMessage = withoutMention,
                        assistantReply = reply,
                    )

                } catch (e: Exception) {
                    log.warn("Failed to persist mention exchange for user {}", event.author.id, e)
                }
                sender.replyLong(event.message, reply)
            }
            .exceptionally { ex ->
                log.error("MentionReplyListener failed", ex)
                event.message.reply(
                    "Desculpe, ocorreu um erro inesperado ao processar sua solicitação."
                ).queue()
                null
            }
    }

    /**
     * Assembles the LLM history from the reply chain (when present) plus the current
     * user turn. Every human/other-bot turn is prefixed with `[name]:` so the voice
     * model can tell speakers apart in multi-user chains.
     *
     * Fallback when the chain is empty:
     *  - if the message is a direct reply to another human, keep the legacy
     *    `[em resposta a ...]` snippet so single-hop replies still carry that context
     *    even when chain resolution couldn't walk further.
     *  - otherwise just the current turn (the original stateless behavior).
     */
    private fun buildHistory(
        chain: List<ChainEntry>,
        referenced: Message?,
        currentUserName: String,
        currentContent: String,
        selfUserId: String,
    ): List<ConversationMessage> = buildList {
        if (chain.isNotEmpty()) {
            chain.forEach { entry ->
                val text = when {
                    entry.role == MessageRole.ASSISTANT -> entry.content
                    entry.isOtherBot -> "[outro bot ${entry.authorName}]: ${entry.content}"
                    else -> "[${entry.authorName}]: ${entry.content}"
                }
                add(ConversationMessage(conversationId = 0L, role = entry.role, content = text))
            }
            add(
                ConversationMessage(
                    conversationId = 0L,
                    role = MessageRole.USER,
                    content = "[$currentUserName]: $currentContent",
                )
            )
        } else {
            // If chain resolution failed but the message is a direct reply, include
            // the referenced message as context. If the referenced message was from
            // Cyrene, add it as an ASSISTANT turn; otherwise for a human add the
            // legacy "[em resposta a ...]" snippet.
            if (referenced != null) {
                if (referenced.author.id == selfUserId) {
                    add(
                        ConversationMessage(
                            conversationId = 0L,
                            role = MessageRole.ASSISTANT,
                            content = referenced.contentRaw,
                        )
                    )
                } else if (!referenced.author.isBot) {
                    add(
                        ConversationMessage(
                            conversationId = 0L,
                            role = MessageRole.USER,
                            content = "[em resposta a ${referenced.author.effectiveName}: " +
                                "\"${referenced.contentRaw.take(500)}\"]",
                        )
                    )
                }
            }
            add(
                ConversationMessage(
                    conversationId = 0L,
                    role = MessageRole.USER,
                    content = currentContent,
                )
            )
        }
    }
}
