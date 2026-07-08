package com.cyrene.discord.util

import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the markdown-safe page splitting: word cap, no lost text, no broken code fences. */
class MessagePaginatorTest {

    // DataSource-less JdbcTemplate: splitting is pure, and the store must fail open anyway.
    private val paginator = MessagePaginator(JdbcTemplate())

    @Test
    fun `short text is a single page`() {
        assertEquals(listOf("oi amor"), paginator.paginate("oi amor"))
    }

    @Test
    fun `long text pages on paragraph boundaries and loses nothing`() {
        val paragraph = (1..60).joinToString(" ") { "palavra$it" }
        val text = (1..8).joinToString("\n\n") { paragraph }
        val pages = paginator.paginate(text)
        assertTrue(pages.size > 1)
        pages.forEach { page ->
            assertTrue(page.split(Regex("\\s+")).size <= MessagePaginator.MAX_WORDS)
        }
        assertEquals(text, pages.joinToString("\n\n"))
    }

    @Test
    fun `code fence is never split across pages`() {
        val filler = (1..195).joinToString(" ") { "w$it" }
        val code = "```kotlin\nval x = 1\nval y = 2\n```"
        val pages = paginator.paginate("$filler\n\n$code")
        assertEquals(2, pages.size)
        assertTrue(pages[1].startsWith("```kotlin"))
        pages.forEach { page ->
            assertEquals(0, Regex("```").findAll(page).count() % 2)
        }
    }

    @Test
    fun `oversized code block is hard split with fences rebalanced`() {
        val code = "```\n" + (1..400).joinToString("\n") { "linha$it" } + "\n```"
        val pages = paginator.paginate(code)
        assertTrue(pages.size > 1)
        pages.forEach { page ->
            assertTrue(page.length <= MessagePaginator.HARD_CHAR_LIMIT + 4)
            assertEquals(0, Regex("```").findAll(page).count() % 2)
        }
    }

    @Test
    fun `render appends the page footer`() {
        assertEquals("a\n-# (1/2)", paginator.render(listOf("a", "b"), 0))
    }

    @Test
    fun `store operations fail open when the database is unreachable`() {
        assertEquals(8, paginator.register("texto").length)
        paginator.linkMessage("chave123", "42")
        assertNull(paginator.pages("chave123"))
        assertNull(paginator.fullTextByMessageId("42"))
    }

    @Test
    fun `page footer regex matches rendered output only at the end`() {
        assertTrue(MessagePaginator.PAGE_FOOTER.containsMatchIn(paginator.render(listOf("a", "b"), 1)))
        assertTrue(!MessagePaginator.PAGE_FOOTER.containsMatchIn("fala de -# (1/2) no meio"))
    }
}
