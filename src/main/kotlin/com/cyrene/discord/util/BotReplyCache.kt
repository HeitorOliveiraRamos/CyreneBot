package com.cyrene.discord.util

import com.cyrene.config.BotProperties
import net.dv8tion.jda.api.entities.Message
import org.springframework.stereotype.Component
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * In-memory LRU of messages the bot has just sent, keyed by Discord message id. Populated
 * from [DiscordMessageSender] success callbacks so reply-chain walks can short-circuit
 * lookups for Cyrene's own prior turns without paying a REST round-trip.
 *
 * Only the data the chain walker needs is retained — not the full JDA [Message] — to avoid
 * pinning JDA-internal references. Entries expire on read (lazy TTL check) and the map is
 * size-capped via [LinkedHashMap.removeEldestEntry], so no background eviction thread is
 * required.
 */
@Component
class BotReplyCache(properties: BotProperties) {

    private val maxSize: Int = properties.reply.chain.cacheMaxSize
    private val ttlNanos: Long = TimeUnit.MINUTES.toNanos(properties.reply.chain.cacheTtlMinutes)

    private data class Entry(
        val content: String,
        val parentMessageId: String?,
        val parentChannelId: String?,
        val storedAtNanos: Long,
    )

    private val cache: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Entry>): Boolean =
                size > maxSize
        },
    )

    /** Records a message the bot just sent (success callback from JDA). */
    fun put(sent: Message) {
        val ref = sent.messageReference
        cache[sent.id] = Entry(
            content = sent.contentRaw,
            parentMessageId = ref?.messageId,
            parentChannelId = ref?.channelId,
            storedAtNanos = System.nanoTime(),
        )
    }

    /** Returns the cached entry if present and not expired; evicts on expiry. */
    fun get(messageId: String): CachedBotMessage? {
        val entry = cache[messageId] ?: return null
        if (System.nanoTime() - entry.storedAtNanos > ttlNanos) {
            cache.remove(messageId)
            return null
        }
        return CachedBotMessage(
            id = messageId,
            content = entry.content,
            parentMessageId = entry.parentMessageId,
            parentChannelId = entry.parentChannelId,
        )
    }
}

/** Plain DTO returned by [BotReplyCache] — decoupled from JDA types. */
data class CachedBotMessage(
    val id: String,
    val content: String,
    val parentMessageId: String?,
    val parentChannelId: String?,
)
