package com.cyrene.discord.util

import com.cyrene.discord.listener.RedoButtonListener
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Splits a long guild reply into ◀ ▶ button-paged messages instead of spamming the channel
 * with a burst of replies. Pages break only on markdown block boundaries — paragraphs, with
 * fenced code blocks kept atomic — so formatting never splits mid-construct; a page may run
 * a bit over or under [MAX_WORDS] because of that.
 *
 * The full answer lives in Postgres (`resposta_paginada`), keyed by a short random id
 * embedded in the button custom id (`pg:<key>:<targetIndex>`), so buttons keep working
 * across restarts. Only the TEXT is stored — pages are re-derived on every click, which
 * means old messages survive splitter tweaks (the listener clamps the index if boundaries
 * moved). The buttons are stateless: every edit re-attaches buttons pointing at the
 * neighbor indices, so there is no cursor to race on. DMs skip pagination entirely and
 * keep the multi-message reply that stays readable forever.
 *
 * All store operations are fail-open like [com.cyrene.knowledge.AnswerCache]: a DB hiccup
 * degrades to [BotMessages.PAGES_EXPIRED] on click, never a failed send.
 */
@Component
class MessagePaginator(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun paginate(text: String): List<String> {
        val pages = mutableListOf<String>()
        val current = StringBuilder()
        var words = 0
        for (block in blocks(text)) {
            val blockWords = countWords(block)
            val overflows = words > 0 &&
                (words + blockWords > MAX_WORDS || current.length + block.length > HARD_CHAR_LIMIT)
            if (overflows) {
                pages += current.toString().trim()
                current.clear()
                words = 0
            }
            current.append(block).append("\n\n")
            words += blockWords
        }
        if (current.isNotBlank()) pages += current.toString().trim()
        return pages.flatMap(::hardSplit)
    }

    /** Stores the full answer and returns the key the buttons carry. */
    fun register(fullText: String): String {
        val key = UUID.randomUUID().toString().take(8)
        try {
            // ponytail: opportunistic retention sweep on write — a scheduler for a table
            // this small is overkill.
            jdbc.update("DELETE FROM resposta_paginada WHERE criado_em < now() - interval '30 days'")
            jdbc.update("INSERT INTO resposta_paginada (chave, texto) VALUES (?, ?)", key, fullText)
        } catch (e: Exception) {
            log.warn("Failed to store paginated reply {}: {}", key, e.message)
        }
        return key
    }

    /** Ties the stored text to the sent Discord message so reply-chain walks can find it. */
    fun linkMessage(key: String, messageId: String) {
        try {
            jdbc.update("UPDATE resposta_paginada SET mensagem_id = ? WHERE chave = ?", messageId, key)
        } catch (e: Exception) {
            log.warn("Failed to link paginated reply {} to message {}: {}", key, messageId, e.message)
        }
    }

    fun pages(key: String): List<String>? = fullTextBy("chave", key)?.let(::paginate)

    /** Full answer behind a paginated bot message, for reply-chain context. */
    fun fullTextByMessageId(messageId: String): String? = fullTextBy("mensagem_id", messageId)

    private fun fullTextBy(column: String, value: String): String? = try {
        jdbc.queryForList(
            "SELECT texto FROM resposta_paginada WHERE $column = ?",
            String::class.java,
            value,
        ).firstOrNull()
    } catch (e: Exception) {
        log.warn("Paginated reply lookup by {} failed: {}", column, e.message)
        null
    }

    fun render(pages: List<String>, index: Int): String =
        "${pages[index]}\n-# (${index + 1}/${pages.size})"

    fun buttons(key: String, index: Int, total: Int): List<Button> = listOf(
        Button.secondary("pg:$key:${index - 1}", "◀").withDisabled(index == 0),
        Button.secondary("pg:$key:${index + 1}", "▶").withDisabled(index == total - 1),
    )

    /** Paragraph-level blocks; fenced code blocks are kept whole even across blank lines. */
    private fun blocks(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inFence = false
        for (line in text.lines()) {
            if (line.trimStart().startsWith("```")) inFence = !inFence
            if (line.isBlank() && !inFence) {
                if (sb.isNotBlank()) result += sb.toString().trimEnd()
                sb.clear()
            } else {
                sb.appendLine(line)
            }
        }
        if (sb.isNotBlank()) result += sb.toString().trimEnd()
        return result
    }

    /**
     * Last resort for a single block bigger than Discord's message cap (giant code block or
     * unbroken paragraph): cut at the last newline/space that fits and re-balance a severed
     * code fence so both halves still render as code.
     */
    private fun hardSplit(page: String): List<String> {
        if (page.length <= HARD_CHAR_LIMIT) return listOf(page)
        val cut = page.lastIndexOf('\n', HARD_CHAR_LIMIT).takeIf { it > 0 }
            ?: page.lastIndexOf(' ', HARD_CHAR_LIMIT).takeIf { it > 0 }
            ?: HARD_CHAR_LIMIT
        var head = page.substring(0, cut).trimEnd()
        var tail = page.substring(cut).trimStart()
        if (FENCE.findAll(head).count() % 2 == 1) {
            head += "\n```"
            tail = "```\n$tail"
        }
        return listOf(head) + hardSplit(tail)
    }

    private fun countWords(block: String): Int = block.trim().split(WHITESPACE).size

    companion object {
        const val MAX_WORDS = 200

        /** Leaves room for the `-# (n/m)` footer under Discord's 2000-char cap. */
        const val HARD_CHAR_LIMIT = 1900

        /** Matches the footer [render] appends — used to spot paginated bot messages. */
        val PAGE_FOOTER = Regex("\\n-# \\(\\d+/\\d+\\)$")

        private val WHITESPACE = Regex("\\s+")
        private val FENCE = Regex("```")
    }
}

/** Handles ◀ ▶ clicks on paginated replies by editing the message in place. */
@Component
class PageButtonListener(private val paginator: MessagePaginator) : ListenerAdapter() {

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = event.componentId
        if (!id.startsWith("pg:")) return
        val key = id.substringAfter("pg:").substringBefore(":")
        val requested = id.substringAfterLast(":").toIntOrNull() ?: return
        val pages = paginator.pages(key)
        if (pages == null) {
            event.reply(BotMessages.PAGES_EXPIRED).setEphemeral(true).queue()
            return
        }
        // Pages are re-derived per click; clamp in case a splitter tweak moved boundaries.
        val index = requested.coerceIn(0, pages.size - 1)
        // Re-attach the 🔄 alongside ◀ ▶: a page edit replaces ALL components, so leaving it
        // off would make Refazer vanish the moment the user turns a page.
        event.editMessage(paginator.render(pages, index))
            .setComponents(
                ActionRow.of(paginator.buttons(key, index, pages.size) + RedoButtonListener.redoButton()),
            )
            .queue()
    }
}
