package com.cyrene.discord.listener

import com.cyrene.ai.InferenceGate
import com.cyrene.ai.OllamaAiService
import com.cyrene.ai.VisionService
import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.ConversationService
import com.cyrene.conversation.MessageRole
import com.cyrene.conversation.UsuarioService
import com.cyrene.discord.ChainEntry
import com.cyrene.discord.ReplyChainResolver
import com.cyrene.discord.tools.DiscordToolContext
import com.cyrene.discord.util.BotMessages
import com.cyrene.discord.util.DiscordMessageSender
import com.cyrene.discord.util.TypingIndicator
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Replies when the bot is @-mentioned in any channel. Skipped when the mentioning user
 * is already in an active `/iniciar-conversa` session (the chat listener handles those).
 *
 * Context is scoped to the Discord reply chain: a fresh @-mention (no reply pointer)
 * starts with a clean slate, while replying to one of Cyrene's messages walks that thread
 * upward so the back-and-forth carries context. Two separate mention threads in the same
 * channel never bleed into each other. The user block resolved by [UsuarioService]
 * (effective name, live highest role and permissions, plus whatever the user asked the bot
 * to remember) is injected as a system block.
 *
 * Moderation tool authority is NOT trusted from the cached flags — `DiscordTools.executeMod`
 * still re-verifies caller permissions live against JDA before acting.
 */
@Component
class MentionReplyListener(
    private val ai: OllamaAiService,
    private val sender: DiscordMessageSender,
    private val conversations: ConversationService,
    private val usuarioService: UsuarioService,
    private val replyChainResolver: ReplyChainResolver,
    private val properties: BotProperties,
    private val executor: Executor,
    private val inferenceGate: InferenceGate,
    private val typingIndicator: TypingIndicator,
    private val visionService: VisionService,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Per-user last-reply timestamps for the [BotProperties.Reply.cooldownSeconds] gate.
     * MUST be concurrent: JDA dispatches `onMessageReceived` on multiple gateway threads,
     * and a plain `HashMap` mutated concurrently can corrupt its internal state (or spin on
     * resize). Pruned in [registerCooldown] so it can't grow without bound on a busy server.
     */
    private val cooldowns = ConcurrentHashMap<String, Long>()

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
            event.message.reply(BotMessages.cooldown(wait)).queue()
            return
        }
        registerCooldown(userId, now, cooldownSeconds)

        // Bound concurrent LLM pipelines: a single Ollama serializes generations, so a burst
        // of mentions would otherwise pile up and slow every reply. When the gate is full,
        // answer immediately in character instead of joining the queue. The cooldown above
        // already paces a single user's retries, so this can't be spammed.
        if (!inferenceGate.tryAcquire()) {
            event.message.reply(BotMessages.busy(event.author.effectiveName)).queue()
            return
        }

        val toolContext = DiscordToolContext(
            callerUserId = event.author.id,
            guildId = if (event.isFromGuild) event.guild.id else null,
            channelId = event.channel.id,
        )

        // Immediate feedback while the (slow, local) LLM pipeline runs; stopped in the
        // same completion handler that releases the gate permit.
        val typing = typingIndicator.start(event.channel)

        try {
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

                        // Image attachments become text (build screenshots etc.) so the rest
                        // of the pipeline stays text-only. Null when vision is disabled or
                        // fails — the reply then proceeds exactly as before.
                        val content = VisionService.augmentContent(
                            withoutMention,
                            visionService.describeFirstImage(event.message),
                        )
                        val history = buildHistory(chain, referenced, currentName, content, selfUser.id)

                        ai.chatBrainAndVoice(
                            history = history,
                            toolContext = toolContext,
                            extraSystemPrompt = resolved?.systemPrompt,
                            userName = resolved?.effectiveName,
                        )
                    },
                    executor,
                )
                // Single completion handler so the gate permit is released exactly once,
                // whether the pipeline succeeded or threw.
                .whenComplete { reply, ex ->
                    try {
                        if (ex != null) {
                            log.error("MentionReplyListener failed", ex)
                            event.message.reply(BotMessages.ERROR).queue()
                        } else {
                            sender.replyLong(event.message, reply)
                        }
                    } finally {
                        typing.close()
                        inferenceGate.release()
                    }
                }
        } catch (e: Exception) {
            // supplyAsync can reject synchronously if the executor is saturated; release the
            // permit we just took so it isn't leaked.
            typing.close()
            inferenceGate.release()
            log.error("MentionReplyListener could not submit work", e)
            event.message.reply(BotMessages.ERROR).queue()
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
            addAll(historyFromChain(chain, currentUserName, currentContent))
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

    /**
     * Records [userId]'s cooldown timestamp, then opportunistically evicts entries older
     * than the cooldown window once the map crosses [COOLDOWN_MAX_ENTRIES] — bounding memory
     * on a busy server without a background sweeper. Expired entries are inert anyway (the
     * gate treats a missing entry the same as a long-ago one), so pruning never changes
     * behaviour.
     */
    private fun registerCooldown(userId: String, now: Long, cooldownSeconds: Long) {
        cooldowns[userId] = now
        if (cooldowns.size > COOLDOWN_MAX_ENTRIES) {
            val cutoff = now - TimeUnit.SECONDS.toMillis(cooldownSeconds)
            cooldowns.entries.removeIf { it.value < cutoff }
        }
    }

    internal companion object {
        /** Soft cap above which [registerCooldown] prunes expired cooldown entries. */
        private const val COOLDOWN_MAX_ENTRIES = 10_000

        /**
         * Pure mapping of a resolved reply [chain] (oldest → newest) plus the current user
         * turn into LLM history. Each human/other-bot turn is prefixed with `[name]:` so the
         * voice model can tell speakers apart; Cyrene's own turns stay unprefixed (they map
         * to the assistant role). Extracted from [buildHistory] so this prefixing contract
         * is unit-testable without a live JDA [Message].
         */
        internal fun historyFromChain(
            chain: List<ChainEntry>,
            currentUserName: String,
            currentContent: String,
        ): List<ConversationMessage> = buildList {
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
        }
    }
}
