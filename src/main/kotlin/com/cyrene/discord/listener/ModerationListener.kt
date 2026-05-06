package com.cyrene.discord.listener

import com.cyrene.ai.OllamaAiService
import com.cyrene.config.BotProperties
import com.cyrene.moderation.ModerationService
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * AI-driven moderation. Disabled by default; enable with `bot.moderation.enabled=true` and
 * point `bot.moderation.channel-id` at the channel to police. Replaces the legacy
 * `ModerationAI` listener (which had the channel ID hardcoded).
 */
@Component
@ConditionalOnProperty(prefix = "bot.moderation", name = ["enabled"], havingValue = "true")
class ModerationListener(
    private val ai: OllamaAiService,
    private val moderation: ModerationService,
    private val properties: BotProperties,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    private val systemPrompt = """
        You are a strict and objective AI moderator. Your task is to assess whether a user's message violates community guidelines due to extreme content.

        Only respond with:
        - 'true' → if the message contains hate speech, threats, extreme harassment, or any other content that is severely inappropriate or harmful.
        - 'false' → for all other cases, including mild rudeness, spam, jokes, or off-topic content.

        Do not explain your answer. Respond only with 'true' or 'false'.
    """.trimIndent()

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        val watchedChannel = properties.moderation.channelId
        if (watchedChannel.isBlank() || event.channel.id != watchedChannel) return

        val content = event.message.contentRaw
        val userId = event.author.id
        val userName = event.author.name
        val guildId = event.guild.id

        CompletableFuture
            .supplyAsync(
                {
                    val response = ai.chatOnce(
                        userMessage = content.lowercase(),
                        extraSystemPrompt = systemPrompt,
                        overrideSystemOnly = true,
                    )
                    classifyInappropriate(response, content)
                },
                executor,
            )
            .thenAccept { inappropriate ->
                if (inappropriate) handleViolation(event, userId, userName, guildId, content)
            }
            .exceptionally { ex ->
                log.error("ModerationListener failed for content \"{}\"", content, ex)
                null
            }
    }

    private fun classifyInappropriate(aiResponse: String, originalContent: String): Boolean {
        val trimmed = aiResponse.trim()
        return when {
            trimmed.equals("true", ignoreCase = true) -> true
            trimmed.equals("false", ignoreCase = true) || trimmed.contains("Falso") -> false
            else -> {
                log.warn(
                    "Unexpected moderation response \"{}\" for content \"{}\"",
                    aiResponse, originalContent
                )
                false
            }
        }
    }

    private fun handleViolation(
        event: MessageReceivedEvent,
        userId: String,
        userName: String,
        guildId: String,
        content: String,
    ) {
        event.message.delete().queue(
            {
                event.channel.sendMessage(
                    "${event.author.asMention}, sua mensagem foi considerada inadequada e removida."
                ).queue()
            },
            { error -> log.error("Failed to delete inappropriate message", error) },
        )

        val warningCount = moderation.addWarning(userId, guildId, content)
        val maxWarnings = properties.moderation.maxWarnings
        val silenced = warningCount >= maxWarnings

        val notification = buildString {
            append(event.guild.name).append("\n")
            append("Notifica que o usuário ").append("***").append(userName).append("***")
                .append(" foi ").append(if (silenced) "mutado" else "avisado")
                .append(" por conteúdo inadequado.\n\n")
            append("**Mensagem que causou o ")
                .append(if (silenced) "silenciamento" else "aviso").append(":** ")
                .append("```").append(content).append("```").append("\n")
            append("**Avisos acumulados:** ").append(warningCount).append("/").append(maxWarnings)
                .append("\n")
        }

        if (silenced) {
            val duration = Duration.ofMinutes(properties.moderation.timeoutMinutes)
            event.guild.timeoutFor(UserSnowflake.fromId(userId), duration).queue(
                { moderation.clearWarnings(userId, guildId) },
                { error ->
                    event.channel.sendMessage(
                        "Erro ao tentar silenciar $userName: ${error.message}"
                    ).queue()
                    log.error("Failed to time out user {}", userId, error)
                },
            )
        }

        event.author.openPrivateChannel().queue(
            { dm -> dm.sendMessage(notification).queue() },
            { error -> log.error("Failed to DM user {} after moderation", userId, error) },
        )
    }
}
