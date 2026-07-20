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

/**
 * Replies privately and returns — the shape every refusal in the moderation commands takes,
 * so a rejected action never spams the channel with the reason.
 */
fun SlashCommandInteractionEvent.replyEphemeral(message: String) {
    reply(message).setEphemeral(true).queue()
}
