package com.cyrene.discord.util

import com.cyrene.config.BotProperties
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import org.springframework.stereotype.Component

@Component
class DiscordMessageSender(private val properties: BotProperties) {

    private val maxLength: Int get() = properties.message.maxLength
    private val ellipsis = "[...]"

    fun sendLong(channel: MessageChannel, content: String) {
        if (containsBlockedMention(content)) {
            channel.sendMessage("não posso fazer isso").queue()
            return
        }
        split(content).forEach { channel.sendMessage(it).queue() }
    }

    fun replyLong(original: Message, content: String) {
        if (containsBlockedMention(content)) {
            original.reply("Não posso fazer isso").queue()
            return
        }
        split(content).forEach { original.reply(it).queue() }
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
