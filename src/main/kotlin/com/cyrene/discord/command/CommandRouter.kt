package com.cyrene.discord.command

import com.cyrene.config.BotProperties
import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CommandRouter(
    commands: List<SlashCommand>,
    private val properties: BotProperties,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val byName: Map<String, SlashCommand> = commands.associateBy { it.name }

    init {
        log.info("Registered {} slash commands: {}", byName.size, byName.keys)
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        val command = byName[event.name] ?: return

        // Channel allow-list ([BotProperties.testChannelIds]). Answered rather than ignored:
        // a slash command that gets no response at all shows the user a red "the application
        // did not respond" error, which reads as a bug instead of a deliberate scope.
        if (!properties.allowsChannel(event.channel.id, event.isFromGuild)) {
            event.reply(BotMessages.CHANNEL_NOT_ALLOWED).setEphemeral(true).queue()
            return
        }

        try {
            command.handle(event)
        } catch (e: Exception) {
            log.error("Error handling /{}", event.name, e)
            if (!event.isAcknowledged) {
                event.reply(BotMessages.ERROR).setEphemeral(true).queue()
            }
        }
    }
}
