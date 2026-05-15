package com.cyrene.discord

import com.cyrene.discord.command.SlashCommand
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Wires runtime concerns onto the [JDA] bean built in [JdaConfig]: attaches all listener
 * beans, waits for the gateway to become ready, registers slash command definitions, and
 * shuts JDA down cleanly with the Spring context.
 */
@Component
class DiscordBootstrap(
    private val jda: JDA,
    private val listeners: List<ListenerAdapter>,
    private val commands: List<SlashCommand>,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun start() {
        log.info("Attaching {} listeners and {} commands to JDA", listeners.size, commands.size)
        listeners.forEach { jda.addEventListener(it) }

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
