package com.cyrene.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bot")
data class BotProperties(
    val token: String,
    val personality: String = "",
    val modelName: String,
    val reply: Reply = Reply(),
    val message: Message = Message(),
    val context: Context = Context(),
    val moderation: Moderation = Moderation(),
) {
    data class Reply(
        val channelId: String = "",
        val cooldownSeconds: Long = 5,
    )

    data class Message(
        val maxLength: Int = 2000,
        val canMentionHereAndEveryone: Boolean = false,
    )

    data class Context(
        val historyFetchSize: Int = 50,
    )

    data class Moderation(
        val enabled: Boolean = false,
        val channelId: String = "",
        val maxWarnings: Int = 3,
        val timeoutMinutes: Long = 15,
    )
}
