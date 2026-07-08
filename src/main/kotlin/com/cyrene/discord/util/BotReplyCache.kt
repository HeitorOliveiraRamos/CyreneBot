package com.cyrene.discord.util

import com.cyrene.config.BotProperties
import net.dv8tion.jda.api.entities.Message
import org.springframework.stereotype.Component
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * In-memory LRU of messages relevant to reply-chain resolution, keyed by Discord message
 * id. Populated from [DiscordMessageSender]: both Cyrene's own sent replies AND the human
 * message each reply answered. That pairing means a reply thread the bot has participated
 * in is fully cached, so [com.cyrene.discord.ReplyChainResolver] can walk it without a
 * single REST round-trip (the REST fallback is only needed after a restart or once an
 * entry has expired).
 *
 * Only the data the chain walker needs is retained — author identity, content and the
 * parent pointer — not the full JDA [Message], to avoid pinning JDA-internal references.
 * Entries expire on read (lazy TTL check) and the map is size-capped via
 * [LinkedHashMap.removeEldestEntry], so no background eviction thread is required.
 */
@Component
class BotReplyCache(properties: BotProperties) {

    private val maxSize: Int = properties.reply.chain.cacheMaxSize
    private val ttlNanos: Long = TimeUnit.MINUTES.toNanos(properties.reply.chain.cacheTtlMinutes)

    private data class Entry(
        val content: String,
        val authorId: String,
        val authorName: String,
        val isBot: Boolean,
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

    /** Records a message relevant to reply-chain walks (a bot reply or a human message
     *  the bot answered). Safe to call repeatedly for the same id — it just refreshes. */
    fun put(message: Message) = put(message, message.contentRaw)

    /** [put] with explicit content — used for button-paginated replies, where the visible
     *  page is only a slice of the full answer the chain walker should see. */
    fun put(message: Message, content: String) {
        val ref = message.messageReference
        val author = message.author
        cache[message.id] = Entry(
            content = content,
            authorId = author.id,
            authorName = author.effectiveName,
            isBot = author.isBot,
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
            authorId = entry.authorId,
            authorName = entry.authorName,
            isBot = entry.isBot,
            parentMessageId = entry.parentMessageId,
            parentChannelId = entry.parentChannelId,
        )
    }
}

/** Plain DTO returned by [BotReplyCache] — decoupled from JDA types. */
data class CachedBotMessage(
    val id: String,
    val content: String,
    val authorId: String,
    val authorName: String,
    val isBot: Boolean,
    val parentMessageId: String?,
    val parentChannelId: String?,
)
