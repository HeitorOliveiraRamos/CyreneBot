package com.cyrene.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the dependency-free HTML->text reduction that turns a fetched wiki page into the
 * plain text the brain reads to reconstruct a kit. This is the most regex-heavy, bug-prone
 * code in the knowledge path, so the stripping and entity-decoding contracts are pinned
 * here. (If this is ever swapped for jsoup, these tests document the expected behaviour.)
 */
class WebSearchClientHtmlTest {

    @Test
    fun `htmlToText strips tags but keeps the text content`() {
        val out = WebSearchClient.htmlToText("<p>Acheron</p><p>elemento Raio</p>")
        assertTrue(out.contains("Acheron"))
        assertTrue(out.contains("elemento Raio"))
        assertFalse(out.contains("<p>"))
    }

    @Test
    fun `htmlToText removes script and style blocks entirely`() {
        val html = "<style>.x{color:red}</style><script>alert(1)</script><p>conteudo</p>"
        val out = WebSearchClient.htmlToText(html)
        assertTrue(out.contains("conteudo"))
        assertFalse(out.contains("alert"))
        assertFalse(out.contains("color:red"))
    }

    @Test
    fun `htmlToText turns block tags into line breaks instead of gluing words`() {
        val out = WebSearchClient.htmlToText("<li>um</li><li>dois</li>")
        assertTrue(out.contains("um"))
        assertTrue(out.contains("dois"))
        assertFalse(out.contains("umdois"))
    }

    @Test
    fun `htmlToText collapses runs of inline whitespace`() {
        assertEquals("a b c", WebSearchClient.htmlToText("<p>a     b\t\tc</p>"))
    }

    @Test
    fun `decodeEntities decodes named and numeric entities`() {
        assertEquals("a & b", WebSearchClient.decodeEntities("a &amp; b"))
        assertEquals("\"x\"", WebSearchClient.decodeEntities("&quot;x&quot;"))
        assertEquals("'y'", WebSearchClient.decodeEntities("&#39;y&#39;"))
        // &#233; is the accented e (U+00E9) common in character/kit names.
        assertEquals("café", WebSearchClient.decodeEntities("caf&#233;"))
    }
}
