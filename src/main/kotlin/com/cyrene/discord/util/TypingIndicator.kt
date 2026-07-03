package com.cyrene.discord.util

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shows the "Cyrene está digitando…" indicator while a reply pipeline runs, so users get
 * immediate feedback instead of silence during a long local-Ollama generation.
 *
 * Discord's typing indicator expires ~10s after each trigger, so [start] re-fires it every
 * 8s until the returned handle is closed. It also clears on its own the moment the bot's
 * reply lands, so closing slightly late is harmless.
 */
@Component
class TypingIndicator {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cyrene-typing").apply { isDaemon = true }
    }

    /** Starts typing in [channel]; close the returned handle when the reply is out. */
    fun start(channel: MessageChannel): AutoCloseable {
        val task = scheduler.scheduleAtFixedRate(
            { runCatching { channel.sendTyping().queue() } },
            0, 8, TimeUnit.SECONDS,
        )
        return AutoCloseable { task.cancel(false) }
    }
}
