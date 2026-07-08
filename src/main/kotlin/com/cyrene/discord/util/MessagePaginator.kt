package com.cyrene.discord.util

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.springframework.stereotype.Component
import java.util.Collections
import java.util.UUID

/**
 * Splits a long guild reply into ◀ ▶ button-paged messages instead of spamming the channel
 * with a burst of replies. Pages break only on markdown block boundaries — paragraphs, with
 * fenced code blocks kept atomic — so formatting never splits mid-construct; a page may run
 * a bit over or under [MAX_WORDS] because of that.
 *
 * Page state lives in an in-memory LRU keyed by a short random id embedded in the button
 * custom id (`pg:<key>:<targetIndex>`). The buttons are stateless: every edit re-attaches
 * buttons pointing at the neighbor indices, so there is no cursor to race on. After a
 * restart or LRU eviction a click gets [BotMessages.PAGES_EXPIRED] — which is why DMs skip
 * pagination entirely and keep the multi-message reply that stays readable forever.
 */
@Component
class MessagePaginator {

    private val store: MutableMap<String, List<String>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, List<String>>): Boolean =
                size > MAX_STORED
        },
    )

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

    /** Stores the pages and returns the key the buttons reference. */
    fun register(pages: List<String>): String =
        UUID.randomUUID().toString().take(8).also { store[it] = pages }

    fun pages(key: String): List<String>? = store[key]

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

        private const val MAX_STORED = 500
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
        val index = id.substringAfterLast(":").toIntOrNull() ?: return
        val pages = paginator.pages(key)
        if (pages == null || index !in pages.indices) {
            event.reply(BotMessages.PAGES_EXPIRED).setEphemeral(true).queue()
            return
        }
        event.editMessage(paginator.render(pages, index))
            .setComponents(ActionRow.of(paginator.buttons(key, index, pages.size)))
            .queue()
    }
}
