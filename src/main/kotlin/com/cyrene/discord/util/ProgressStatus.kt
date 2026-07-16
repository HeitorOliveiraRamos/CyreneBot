package com.cyrene.discord.util

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Live status line for a running reply pipeline: the first update replies to [original],
 * later updates edit that same reply in place, and [close] deletes it once the real answer
 * is out — so the user sees "🔎 procurando…" → "🌐 pesquisando…" → the actual reply, never
 * leftover status clutter.
 *
 * The status message carries a "Cancelar" button. A click by the requester flips
 * [isCancelled]: the pipeline aborts at its next stage boundary (the flag check in
 * [invoke] throws), the completion handlers suppress the final reply, and the status line
 * stays behind edited to [BotMessages.CANCELLED]. Cancellation is cooperative — a
 * generation already in flight still runs to completion on the Ollama side, it just gets
 * discarded. The registry is in-memory only: a restart kills the pipeline the button
 * would cancel anyway.
 *
 * Updates use blocking `complete()` on purpose: they are invoked from the AI executor thread
 * (never a JDA gateway thread, where `complete()` would throw), they arrive seconds apart
 * (each marks an LLM/network stage), and blocking keeps edits ordered with zero race
 * handling. Every Discord call is wrapped so a REST hiccup can only lose a status line,
 * never the reply pipeline.
 */
class ProgressStatus(private val original: Message) : (String) -> Unit, AutoCloseable {

    private var status: Message? = null
    private val key = UUID.randomUUID().toString().take(8)

    @Volatile
    var isCancelled = false
        private set

    init {
        ACTIVE[key] = this
    }

    override fun invoke(text: String) {
        if (isCancelled) throw CancellationException("pedido cancelado pelo usuário")
        runCatching {
            val current = status
            if (current == null) {
                status = original.reply(text)
                    .setComponents(ActionRow.of(Button.danger("cancel:$key", "X")))
                    .complete()
            } else {
                current.editMessage(text).complete()
            }
        }.onFailure { log.debug("Status update '{}' failed", text, it) }
    }

    /**
     * Cancel-button click. Only the user whose message started the pipeline may cancel;
     * returns false for anyone else so the listener can answer ephemerally.
     */
    fun cancel(event: ButtonInteractionEvent): Boolean {
        if (event.user.id != original.author.id) return false
        isCancelled = true
        event.editMessage(BotMessages.CANCELLED).setComponents().queue()
        return true
    }

    override fun close() {
        ACTIVE.remove(key)
        // A cancelled run keeps its "Cancelando..." line as the visible outcome.
        if (!isCancelled) status?.let { msg -> runCatching { msg.delete().queue() } }
        status = null
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProgressStatus::class.java)

        private val ACTIVE = ConcurrentHashMap<String, ProgressStatus>()

        fun byKey(key: String): ProgressStatus? = ACTIVE[key]
    }
}

/** Handles "Cancelar" clicks on live status messages. */
@Component
class CancelButtonListener : ListenerAdapter() {

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = event.componentId
        if (!id.startsWith("cancel:")) return
        val status = ProgressStatus.byKey(id.removePrefix("cancel:"))
        when {
            // Pipeline already finished (or the bot restarted): the status message is
            // about to vanish — just ack the click.
            status == null -> event.deferEdit().queue()
            !status.cancel(event) -> event.reply(BotMessages.NOT_YOUR_BUTTON).setEphemeral(true).queue()
        }
    }
}
