package com.cyrene.discord

import com.cyrene.config.BotProperties
import com.cyrene.conversation.MessageRole
import com.cyrene.discord.util.BotReplyCache
import com.cyrene.discord.util.CachedBotMessage
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Walks a Discord reply chain upward from a given message and returns the prior turns in
 * oldest-first order so they can be injected as conversation history.
 *
 * Three-tier lookup per hop, fastest first:
 *  1. [Message.getReferencedMessage] from the gateway event (free — hop 1 ships inline
 *     with the MESSAGE_CREATE payload Discord already sent; REST-fetched parents also
 *     carry their own referenced message inline).
 *  2. [BotReplyCache] — Cyrene's own replies AND the human messages she answered, so an
 *     in-session thread resolves entirely from memory (~µs lookup, no REST).
 *  3. [MessageChannel.retrieveMessageById] REST fallback for hops not in cache (e.g. after
 *     a restart). Blocks on `.complete()` — must only be called from the AI executor.
 *
 * Because the cache covers both sides of a thread, the common case never touches REST, so
 * the walk stays cheap even for long threads. It is still bounded by
 * [BotProperties.ReplyChain.maxHops] AND [BotProperties.ReplyChain.budgetMs] so a deep
 * chain that DOES fall through to REST (cold cache) can't blow the latency budget.
 *
 * The walker is intentionally *fail-soft*: any error fetching a parent (deleted message,
 * permissions, REST 404) stops the walk and returns whatever was collected so far. The
 * caller falls back to stateless behavior in that case.
 */
@Component
class ReplyChainResolver(
    private val botReplyCache: BotReplyCache,
    private val properties: BotProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param start the message that triggered the reply (NOT included in the result —
     *   the caller appends it as the current user turn).
     * @param selfUserId Cyrene's own user id, used to distinguish self-bot turns
     *   (ASSISTANT role) from other bots' messages (USER role, collapsed).
     */
    fun resolveChain(start: Message, selfUserId: String): List<ChainEntry> {
        val maxHops = properties.reply.chain.maxHops
        if (maxHops <= 0) return emptyList()

        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(properties.reply.chain.budgetMs)

        val collected = mutableListOf<ChainEntry>()
        var nextMessageId: String? = start.messageReference?.messageId
        var nextChannelId: String? = start.messageReference?.channelId
        // Hop 1 is free when Discord ships referenced_message inline with the event.
        var preloaded: Message? = start.referencedMessage
        var hops = 0

        while (nextMessageId != null && hops < maxHops) {
            if (System.nanoTime() > deadlineNanos) {
                log.debug("Reply chain walk hit time budget after {} hops", hops)
                break
            }

            // Tier 1: full JDA Message already in hand (inline referenced message).
            val full = preloaded
            if (full != null) {
                collected += toChainEntry(full, selfUserId)
                val parentRef = full.messageReference
                nextMessageId = parentRef?.messageId
                nextChannelId = parentRef?.channelId
                preloaded = full.referencedMessage
                hops++
                continue
            }

            // Tier 2: in-memory cache — covers both Cyrene's turns and the human messages
            // she answered, so an in-session thread resolves without any REST.
            val cached = botReplyCache.get(nextMessageId)
            if (cached != null) {
                collected += cached.toChainEntry(selfUserId)
                nextMessageId = cached.parentMessageId
                nextChannelId = cached.parentChannelId
                preloaded = null
                hops++
                continue
            }

            // Tier 3: REST fallback for hops not in cache (cold cache / restart).
            val fetched = tryFetchMessage(start.channel, nextMessageId, nextChannelId)
            if (fetched != null) {
                collected += toChainEntry(fetched, selfUserId)
                val parentRef = fetched.messageReference
                nextMessageId = parentRef?.messageId
                nextChannelId = parentRef?.channelId
                preloaded = fetched.referencedMessage
                hops++
                continue
            }

            // Nothing worked for this hop — stop walking and use what we have.
            log.debug("Could not resolve hop {} (messageId={}) — stopping walk", hops, nextMessageId)
            break
        }

        return collected.asReversed()
    }

    private fun toChainEntry(message: Message, selfUserId: String): ChainEntry {
        val author = message.author
        return when {
            author.id == selfUserId -> ChainEntry(
                authorName = SELF_AUTHOR_LABEL,
                content = message.contentRaw,
                role = MessageRole.ASSISTANT,
            )
            author.isBot -> ChainEntry(
                authorName = author.effectiveName,
                content = message.contentRaw,
                role = MessageRole.USER,
                isOtherBot = true,
            )
            else -> ChainEntry(
                authorName = author.effectiveName,
                content = message.contentRaw,
                role = MessageRole.USER,
            )
        }
    }

    /** Same mapping as [toChainEntry] but from a cached entry (no JDA [Message] needed). */
    private fun CachedBotMessage.toChainEntry(selfUserId: String): ChainEntry = when {
        authorId == selfUserId -> ChainEntry(
            authorName = SELF_AUTHOR_LABEL,
            content = content,
            role = MessageRole.ASSISTANT,
        )
        isBot -> ChainEntry(
            authorName = authorName,
            content = content,
            role = MessageRole.USER,
            isOtherBot = true,
        )
        else -> ChainEntry(
            authorName = authorName,
            content = content,
            role = MessageRole.USER,
        )
    }

    private fun tryFetchMessage(
        currentChannel: MessageChannel,
        messageId: String,
        parentChannelId: String?,
    ): Message? {
        // Reply chains effectively stay in the same channel; if Discord ever reports a
        // cross-channel reference we can't easily resolve, skip rather than hunt around.
        if (parentChannelId != null && parentChannelId != currentChannel.id) {
            log.debug(
                "Skipping cross-channel reply hop (parentChannel={}, currentChannel={})",
                parentChannelId, currentChannel.id,
            )
            return null
        }
        return try {
            currentChannel.retrieveMessageById(messageId).complete()
        } catch (e: Exception) {
            log.debug("REST fetch failed for message {}: {}", messageId, e.message)
            null
        }
    }

    companion object {
        /** Stable label used for self-bot turns so the LLM consistently recognizes its own voice. */
        const val SELF_AUTHOR_LABEL: String = "Cyrene"
    }
}

/**
 * One entry in a reconstructed reply chain.
 *
 *  - [role] is [MessageRole.ASSISTANT] only for Cyrene's own turns. Every human or
 *    other-bot turn maps to [MessageRole.USER] so the prompt stays well-formed for the
 *    voice model.
 *  - [isOtherBot] flags messages from third-party bots so the caller can prefix them
 *    distinctly (avoiding the model confusing them with its own prior output).
 */
data class ChainEntry(
    val authorName: String,
    val content: String,
    val role: MessageRole,
    val isOtherBot: Boolean = false,
)
