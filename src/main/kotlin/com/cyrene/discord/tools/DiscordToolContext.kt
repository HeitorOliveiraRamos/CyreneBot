package com.cyrene.discord.tools

/**
 * Identifies who is invoking the LLM and where, so [DiscordTools] can perform Discord
 * actions scoped to the right caller / guild / channel and enforce permission checks
 * against the actual invoker — not whatever the model claims in its arguments.
 *
 * Threaded into Spring AI's `ToolContext` under the [KEY] entry; tools pull it back out
 * to resolve JDA entities and validate authority before any action.
 */
data class DiscordToolContext(
    val callerUserId: String,
    /** Guild snowflake; null when the conversation is a DM. Server-scoped tools must reject null. */
    val guildId: String?,
    val channelId: String,
) {
    companion object {
        const val KEY = "discord"
    }
}
