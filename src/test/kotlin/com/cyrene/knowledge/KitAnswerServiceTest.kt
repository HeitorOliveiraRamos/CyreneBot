package com.cyrene.knowledge

import com.cyrene.knowledge.KitAnswerService.KitAsk
import com.cyrene.knowledge.KitAnswerService.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the pure core of the deterministic kit path: player vocabulary → which
 * ability/eidolon docs are asked for ([KitAnswerService.wantedKit]) and the verbatim
 * render ([KitAnswerService.renderKit]).
 */
class KitAnswerServiceTest {

    private val skills = listOf(
        "Robin — habilidade: Bater de Asas Silencioso (ATQ Básico)\nCausa dano.",
        "Robin — habilidade: Ária das Penas (Perícia)\nBuffa o time.",
        "Robin — habilidade: Vox Harmonique, Opus Cosmique (Perícia Suprema)\nEntra em Concerto.",
        "Robin — habilidade: Ressonância Tonal (Talento)\nATQ extra.",
        "Robin — habilidade: Prelúdio da Inebriação (Técnica)\nBuff inicial.",
    )

    private val eidolons = listOf(
        "Acheron — Eidolon 2: Trovões Mudos\nSPD +20%.",
        "Acheron — Eidolon 1: Verdadeiras Palavras\nCRIT +18%.",
    )

    // -------------------- wantedKit (routing) -------------------- //

    @Test
    fun `player shorthand routes to the right ability kind`() {
        assertEquals(
            KitAsk(setOf(SkillKind.ULTIMATE), null),
            KitAnswerService.wantedKit("o que a ult da robin faz?"),
        )
        assertEquals(
            KitAsk(setOf(SkillKind.TALENT), null),
            KitAnswerService.wantedKit("qual o talento do welt?"),
        )
        assertEquals(
            KitAsk(setOf(SkillKind.BASIC), null),
            KitAnswerService.wantedKit("o ataque básico da acheron é bom?"),
        )
    }

    @Test
    fun `pericia suprema is only the ultimate, bare pericia is the skill`() {
        assertEquals(
            KitAsk(setOf(SkillKind.ULTIMATE), null),
            KitAnswerService.wantedKit("o que faz a perícia suprema da robin?"),
        )
        assertEquals(
            KitAsk(setOf(SkillKind.SKILL), null),
            KitAnswerService.wantedKit("o que faz a perícia da robin?"),
        )
    }

    @Test
    fun `eidolon shorthand carries the numbers, plural means all`() {
        assertEquals(
            KitAsk(emptySet(), setOf(1)),
            KitAnswerService.wantedKit("o que a e1 da acheron faz?"),
        )
        assertEquals(
            KitAsk(emptySet(), setOf(2)),
            KitAnswerService.wantedKit("efeito do eidolon 2 da acheron"),
        )
        assertEquals(
            KitAsk(emptySet(), emptySet<Int>()),
            KitAnswerService.wantedKit("quais os eidolons da acheron?"),
        )
    }

    @Test
    fun `plural habilidades asks for the whole ability set`() {
        assertEquals(
            SkillKind.entries.toSet(),
            KitAnswerService.wantedKit("quais as habilidades da robin?")?.kinds,
        )
    }

    @Test
    fun `build vocabulary excludes the kit path, and vice versa nothing routes twice`() {
        // Mixed families = composition job for the LLM path.
        assertNull(KitAnswerService.wantedKit("qual a build e a ult do welt?"))
        // Pure build questions never reach the kit path.
        assertNull(KitAnswerService.wantedKit("qual a build do phainon?"))
        // Non-kit questions don't route.
        assertNull(KitAnswerService.wantedKit("quem é a bronya?"))
        assertNull(KitAnswerService.wantedKit("qual o traço do welt?"))
    }

    // -------------------- renderKit (template) -------------------- //

    @Test
    fun `renders the asked ability verbatim under a bold header`() {
        assertEquals(
            "**Robin — habilidade: Vox Harmonique, Opus Cosmique (Perícia Suprema)**\nEntra em Concerto.",
            KitAnswerService.renderKit(skills, emptyList(), KitAsk(setOf(SkillKind.ULTIMATE), null)),
        )
    }

    @Test
    fun `whole ability set renders in canonical kit order`() {
        val out = KitAnswerService.renderKit(skills.shuffled(), emptyList(), KitAsk(SkillKind.entries.toSet(), null))!!
        val order = listOf("ATQ Básico", "Perícia)", "Perícia Suprema", "Talento", "Técnica")
        val positions = order.map { out.indexOf(it) }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun `eidolons filter by number and sort ascending when all are asked`() {
        assertEquals(
            "**Acheron — Eidolon 1: Verdadeiras Palavras**\nCRIT +18%.",
            KitAnswerService.renderKit(emptyList(), eidolons, KitAsk(emptySet(), setOf(1))),
        )
        val all = KitAnswerService.renderKit(emptyList(), eidolons, KitAsk(emptySet(), emptySet()))!!
        assertEquals(
            "**Acheron — Eidolon 1: Verdadeiras Palavras**\nCRIT +18%.\n\n" +
                "**Acheron — Eidolon 2: Trovões Mudos**\nSPD +20%.",
            all,
        )
    }

    @Test
    fun `asked docs the character lacks render null so the caller falls through`() {
        assertNull(KitAnswerService.renderKit(skills, emptyList(), KitAsk(emptySet(), setOf(3))))
        assertNull(KitAnswerService.renderKit(emptyList(), eidolons, KitAsk(setOf(SkillKind.ULTIMATE), null)))
    }

    @Test
    fun `type tag is the last parenthesized group of the header`() {
        assertEquals("Perícia Suprema", KitAnswerService.typeTag(skills[2]))
        // A skill NAME with parentheses must not confuse the tag.
        assertEquals(
            "Talento",
            KitAnswerService.typeTag("X — habilidade: Nome (Estranho) da Skill (Talento)\ndesc"),
        )
    }
}
