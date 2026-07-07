package com.cyrene.discord.util

import net.dv8tion.jda.api.entities.Message
import org.slf4j.LoggerFactory

/**
 * Live status line for a running reply pipeline: the first update replies to [original],
 * later updates edit that same reply in place, and [close] deletes it once the real answer
 * is out — so the user sees "🔎 procurando…" → "🌐 pesquisando…" → the actual reply, never
 * leftover status clutter.
 *
 * Updates use blocking `complete()` on purpose: they are invoked from the AI executor thread
 * (never a JDA gateway thread, where `complete()` would throw), they arrive seconds apart
 * (each marks an LLM/network stage), and blocking keeps edits ordered with zero race
 * handling. Every Discord call is wrapped so a REST hiccup can only lose a status line,
 * never the reply pipeline.
 */
class ProgressStatus(private val original: Message) : (String) -> Unit, AutoCloseable {

    private var status: Message? = null

    override fun invoke(text: String) {
        runCatching {
            val current = status
            if (current == null) {
                status = original.reply(text).complete()
            } else {
                current.editMessage(text).complete()
            }
        }.onFailure { log.debug("Status update '{}' failed", text, it) }
    }

    override fun close() {
        status?.let { msg -> runCatching { msg.delete().queue() } }
        status = null
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ProgressStatus::class.java)
    }
}
