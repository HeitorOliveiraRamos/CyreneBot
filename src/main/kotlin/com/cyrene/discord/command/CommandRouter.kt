package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CommandRouter(commands: List<SlashCommand>) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val byName: Map<String, SlashCommand> = commands.associateBy { it.name }

    init {
        log.info("Registered {} slash commands: {}", byName.size, byName.keys)
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        val command = byName[event.name] ?: return
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
