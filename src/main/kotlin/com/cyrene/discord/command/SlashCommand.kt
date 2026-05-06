package com.cyrene.discord.command

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData

/**
 * A single slash command. Implementations are picked up automatically as Spring beans,
 * registered with Discord at startup, and routed to by [CommandRouter].
 */
interface SlashCommand {
    val name: String
    val definition: CommandData
    fun handle(event: SlashCommandInteractionEvent)
}
