package com.cyrene.discord.util

import com.cyrene.config.BotProperties
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.InteractionHook
import org.springframework.stereotype.Component

@Component
class DiscordMessageSender(
    private val properties: BotProperties,
    private val botReplyCache: BotReplyCache,
) {

    private val maxLength: Int get() = properties.message.maxLength
    private val ellipsis = "[...]"

    /**
     * Last-resort text used when an upstream pipeline hands us a blank string. JDA's
     * `sendMessage("")` throws — without this guard the whole async chain fails after
     * a moderation action has already taken effect server-side, which is the worst
     * possible failure mode (the user thinks nothing happened).
     */
    private val blankFallback = "Pronto."

    fun replyLong(original: Message, content: String) {
        // Cache the human message we're replying to so the next turn's reply-chain walk
        // resolves this hop from memory instead of a REST fetch.
        botReplyCache.put(original)
        if (containsBlockedMention(content)) {
            original.reply("Não posso fazer isso").queue(botReplyCache::put)
            return
        }
        val safe = content.ifBlank { blankFallback }
        split(safe).forEach { original.reply(it).queue(botReplyCache::put) }
    }

    /**
     * [replyLong] for deferred slash-command interactions. Sent messages are cached in
     * [BotReplyCache] so a user replying to an /hsr answer gets reply-chain context.
     */
    fun sendLong(hook: InteractionHook, content: String) {
        if (containsBlockedMention(content)) {
            hook.sendMessage("Não posso fazer isso").queue()
            return
        }
        val safe = content.ifBlank { blankFallback }
        split(safe).forEach { hook.sendMessage(it).queue(botReplyCache::put) }
    }

    private fun containsBlockedMention(text: String): Boolean =
        !properties.message.canMentionHereAndEveryone &&
            (text.contains("@everyone") || text.contains("@here"))

    private fun split(message: String): List<String> {
        if (message.length <= maxLength) return listOf(message)

        val parts = mutableListOf<String>()
        var index = 0
        while (index < message.length) {
            val remaining = message.length - index
            val maxPartLength =
                if (remaining > maxLength) maxLength - ellipsis.length else maxLength

            var end = index + maxPartLength
            if (end < message.length) {
                val lastSpace = message.lastIndexOf(' ', end)
                if (lastSpace > index) end = lastSpace
            } else {
                end = message.length
            }

            var part = message.substring(index, end).trim()
            index = end

            if (index < message.length) {
                part += ellipsis
                while (index < message.length && message[index] == ' ') index++
            }
            parts += part
        }
        return parts
    }
}
