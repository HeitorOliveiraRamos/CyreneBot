package com.cyrene.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the answer cache's two pure contracts: the normalized key (trivially-different
 * phrasings of the same question share one entry) and the vocative-leak guard (an answer
 * carrying the asker's name must never be served to someone else).
 */
class AnswerCacheTest {

    @Test
    fun `normalizeKey folds accents, case, punctuation and whitespace into one key`() {
        val expected = "quem e a acheron"
        assertEquals(expected, AnswerCache.normalizeKey("Quem é a Acheron?"))
        assertEquals(expected, AnswerCache.normalizeKey("  QUEM   É a ACHERON!! "))
        assertEquals(expected, AnswerCache.normalizeKey("quem e a acheron"))
    }

    @Test
    fun `normalizeKey strips the mention path's speaker tag`() {
        assertEquals("quem e a acheron", AnswerCache.normalizeKey("[Heitor]: Quem é a Acheron?"))
    }

    @Test
    fun `normalizeKey keeps distinct questions distinct`() {
        // The reason this cache is exact-match: these two must never share an entry.
        assertTrue(
            AnswerCache.normalizeKey("o que faz o e1 da acheron?") !=
                AnswerCache.normalizeKey("o que faz o e2 da acheron?"),
        )
    }

    @Test
    fun `normalizeKey is empty for blank input`() {
        assertEquals("", AnswerCache.normalizeKey("   "))
        assertEquals("", AnswerCache.normalizeKey("?!"))
    }

    @Test
    fun `shouldStore rejects an answer that carries the asking user's name`() {
        assertFalse(AnswerCache.shouldStore("Claro, Heitor! Acheron é do Raio.", "Heitor"))
        // Case-insensitive: a lowercased vocative still leaks.
        assertFalse(AnswerCache.shouldStore("claro, heitor! Acheron é do Raio.", "Heitor"))
    }

    @Test
    fun `shouldStore accepts name-free answers and anonymous askers`() {
        assertTrue(AnswerCache.shouldStore("Acheron é do elemento Raio.", "Heitor"))
        assertTrue(AnswerCache.shouldStore("Qualquer resposta.", null))
        assertTrue(AnswerCache.shouldStore("Qualquer resposta.", ""))
    }
}
