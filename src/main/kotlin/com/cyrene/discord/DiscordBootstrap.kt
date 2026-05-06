package com.cyrene.discord

import com.cyrene.config.BotProperties
import com.cyrene.discord.command.SlashCommand
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Owns the JDA lifecycle: builds the client, attaches all listener beans, registers slash
 * command definitions on startup, and shuts JDA down cleanly with the Spring context.
 */
@Component
class DiscordBootstrap(
    private val properties: BotProperties,
    private val listeners: List<ListenerAdapter>,
    private val commands: List<SlashCommand>,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private lateinit var jda: JDA

    @PostConstruct
    fun start() {
        log.info("Starting JDA with {} listeners and {} commands", listeners.size, commands.size)

        val builder = JDABuilder.createDefault(
            properties.token,
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.DIRECT_MESSAGES,
            GatewayIntent.GUILD_MEMBERS,
        )
        listeners.forEach { builder.addEventListeners(it) }
        jda = builder.build()

        jda.awaitReady()

        jda.updateCommands()
            .addCommands(commands.map { it.definition })
            .queue { log.info("Slash commands registered with Discord") }
    }

    @PreDestroy
    fun stop() {
        log.info("Shutting down JDA")
        jda.shutdown()
        try {
            jda.awaitShutdown()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        log.info("Bot desligado.")
    }
}
