package com.cyrene.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the roster-guard primitives — the pure heart of the anti-invention gate.
 * [KnowledgeGrounder.entitySubject] pulls the subject out of a bare "quem é X" question, and
 * [KnowledgeGrounder.subjectMentioned] decides whether the retrieved source actually names it.
 * Together they're what turns "found something vaguely related" into an honest abstain for a
 * character that doesn't exist (the "Lilita" case).
 */
class KnowledgeGrounderTest {

    @Test
    fun `entitySubject extracts the name from bare who-is questions`() {
        assertEquals("lilita", KnowledgeGrounder.entitySubject("quem é lilita?"))
        assertEquals("acheron", KnowledgeGrounder.entitySubject("quem e a Acheron")?.lowercase())
        assertEquals("jing yuan", KnowledgeGrounder.entitySubject("o que é Jing Yuan?")?.lowercase())
        assertEquals("march 7th", KnowledgeGrounder.entitySubject("who is March 7th")?.lowercase())
    }

    @Test
    fun `entitySubject strips a leading article`() {
        assertEquals("acheron", KnowledgeGrounder.entitySubject("quem é a acheron"))
        assertEquals("herta", KnowledgeGrounder.entitySubject("quem é the herta")?.lowercase())
    }

    @Test
    fun `entitySubject returns null for non-entity questions (guard does not apply)`() {
        // Builds, teams, mechanics — the subject must appear naturally, the roster guard is moot.
        assertNull(KnowledgeGrounder.entitySubject("qual o melhor cone pro Dan Heng?"))
        assertNull(KnowledgeGrounder.entitySubject("em quais personagens esse set é melhor?"))
        assertNull(KnowledgeGrounder.entitySubject("monta um time pra Acheron"))
        assertNull(KnowledgeGrounder.entitySubject("oi tudo bem"))
    }

    @Test
    fun `subjectMentioned is true when the source names the subject`() {
        assertTrue(KnowledgeGrounder.subjectMentioned("acheron", "Acheron é do elemento Raio, caminho Nihility."))
        // Multi-word: any significant token present is enough (sources may abbreviate the name).
        assertTrue(KnowledgeGrounder.subjectMentioned("dan heng", "O Dan Heng usa lança de Vento."))
    }

    @Test
    fun `subjectMentioned is false when the subject is absent — the invention catch`() {
        // "Lilita" doesn't exist; whatever the search returned never names her → abstain.
        assertFalse(
            KnowledgeGrounder.subjectMentioned(
                "lilita",
                "[Acheron] Acheron é uma personagem do elemento Raio do caminho Nihility.",
            ),
        )
    }

    @Test
    fun `subjectMentioned passes when it cannot judge (all-short subject)`() {
        // No token >= 3 chars to discriminate on → never block on uncertainty.
        assertTrue(KnowledgeGrounder.subjectMentioned("yu", "conteúdo qualquer sem o nome"))
    }
}
