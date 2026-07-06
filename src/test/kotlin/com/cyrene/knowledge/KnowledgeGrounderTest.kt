package com.cyrene.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the roster-guard primitives — the pure heart of the anti-invention gate.
 * [KnowledgeGrounder.entitySubjects] pulls every asked-about name out of a question, and
 * [KnowledgeGrounder.subjectMentioned] decides whether the retrieved source actually names it.
 * Together they're what turns "found something vaguely related" into an honest abstain for a
 * character that doesn't exist (the "Lilita" and "Cora/Carmilla/Sylph" cases).
 */
class KnowledgeGrounderTest {

    @Test
    fun `entitySubjects extracts a single name from a bare who-is question`() {
        assertEquals(listOf("lilita"), KnowledgeGrounder.entitySubjects("quem é lilita?"))
        assertEquals(listOf("acheron"), KnowledgeGrounder.entitySubjects("quem e a Acheron").map { it.lowercase() })
        assertEquals(listOf("jing yuan"), KnowledgeGrounder.entitySubjects("o que é Jing Yuan?").map { it.lowercase() })
        assertEquals(listOf("march 7th"), KnowledgeGrounder.entitySubjects("who is March 7th").map { it.lowercase() })
    }

    @Test
    fun `entitySubjects extracts every name from a multi-question batch — the reported failure`() {
        val subjects = KnowledgeGrounder
            .entitySubjects("quem é cora? quem é luna nova? quem é carmilla? quem é tenebra? quem é sylph?")
            .map { it.lowercase() }
        assertEquals(listOf("cora", "luna nova", "carmilla", "tenebra", "sylph"), subjects)
    }

    @Test
    fun `entitySubjects splits a comma-and-e list under one opener`() {
        assertEquals(
            listOf("cora", "carmilla", "sylph"),
            KnowledgeGrounder.entitySubjects("quem é cora, carmilla e sylph?").map { it.lowercase() },
        )
    }

    @Test
    fun `entitySubjects strips a leading article`() {
        assertEquals(listOf("acheron"), KnowledgeGrounder.entitySubjects("quem é a acheron"))
        assertEquals(listOf("herta"), KnowledgeGrounder.entitySubjects("quem é the herta").map { it.lowercase() })
    }

    @Test
    fun `entitySubjects is empty for non-entity questions (guard does not apply)`() {
        assertTrue(KnowledgeGrounder.entitySubjects("qual o melhor cone pro Dan Heng?").isEmpty())
        assertTrue(KnowledgeGrounder.entitySubjects("em quais personagens esse set é melhor?").isEmpty())
        assertTrue(KnowledgeGrounder.entitySubjects("monta um time pra Acheron").isEmpty())
        assertTrue(KnowledgeGrounder.entitySubjects("oi tudo bem").isEmpty())
    }

    @Test
    fun `subjectMentioned is true when the source names the subject`() {
        assertTrue(KnowledgeGrounder.subjectMentioned("acheron", "Acheron é do elemento Raio, caminho Nihility."))
        // Multi-word: every significant token must appear (sources spell the full name).
        assertTrue(KnowledgeGrounder.subjectMentioned("dan heng", "O Dan Heng usa lança de Vento."))
    }

    @Test
    fun `subjectMentioned is false when the subject is absent — the invention catch`() {
        assertFalse(
            KnowledgeGrounder.subjectMentioned(
                "lilita",
                "[Acheron] Acheron é uma personagem do elemento Raio do caminho Nihility.",
            ),
        )
    }

    @Test
    fun `subjectMentioned does not match a name as a substring of another word`() {
        // The exact bug: "cora" must NOT be considered present just because "coração" exists.
        assertFalse(KnowledgeGrounder.subjectMentioned("cora", "Ela tem um coração puro e gentil."))
    }

    @Test
    fun `subjectMentioned requires all tokens, so one common word cannot ground a fake name`() {
        // "nova" appears in "vida nova" but "luna" does not → not grounded.
        assertFalse(KnowledgeGrounder.subjectMentioned("luna nova", "Ela traz vida nova ao mundo."))
    }

    @Test
    fun `subjectMentioned passes when it cannot judge (all-short subject)`() {
        assertTrue(KnowledgeGrounder.subjectMentioned("yu", "conteúdo qualquer sem o nome"))
    }

    @Test
    fun `subjectGrounded accepts a subject named in the context only under a localized alias`() {
        // "quem é March 7th?" grounded against a pt-BR chunk that says "Março 7".
        assertTrue(
            KnowledgeGrounder.subjectGrounded(
                "march 7th",
                "Março 7 é uma personagem do caminho da Preservação.",
                aliases = listOf("March 7th", "Março 7", "Marzo 7"),
            ),
        )
    }

    @Test
    fun `subjectGrounded still fails a fake name — no aliases means no loosening`() {
        assertFalse(
            KnowledgeGrounder.subjectGrounded(
                "lilita",
                "Acheron é uma personagem do elemento Raio.",
                aliases = emptyList(),
            ),
        )
    }

    @Test
    fun `enrichQuery appends only the aliases the query does not already use`() {
        assertEquals(
            "quem é a acheron? (Verdadeiro Nome)",
            KnowledgeGrounder.enrichQuery("quem é a acheron?", listOf("Acheron", "Verdadeiro Nome")),
        )
        // Duplicated aliases (en == pt == es) collapse; nothing to add → query untouched.
        assertEquals(
            "quem é a acheron?",
            KnowledgeGrounder.enrichQuery("quem é a acheron?", listOf("Acheron", "Acheron", "Acheron")),
        )
        assertEquals("qual o melhor time?", KnowledgeGrounder.enrichQuery("qual o melhor time?", emptyList()))
    }
}
