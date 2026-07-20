package com.cyrene.knowledge

import com.cyrene.hsr.NamedText
import com.cyrene.hsr.PersonagemHsr
import com.cyrene.knowledge.KitAnswerService.KitAsk
import com.cyrene.knowledge.KitAnswerService.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the pure core of the deterministic kit path: player vocabulary → which
 * ability/eidolon pieces are asked for ([KitAnswerService.wantedKit]) and the render
 * straight from the [PersonagemHsr] columns ([KitAnswerService.renderKit]).
 */
class KitAnswerServiceTest {

    private val robin = PersonagemHsr(
        characterId = "1309",
        nome = "Robin",
        raridade = 5,
        caminho = "Harmonia (Harmony)",
        elemento = "Físico (Physical)",
        descricao = "Uma cantora.",
        atqBasico = NamedText("Bater de Asas Silencioso", "Causa dano."),
        pericia = NamedText("Ária das Penas", "Buffa o time."),
        periciaSuprema = NamedText("Vox Harmonique, Opus Cosmique", "Entra em Concerto."),
        talento = NamedText("Ressonância Tonal", "ATQ extra."),
        tecnica = NamedText("Prelúdio da Inebriação", "Buff inicial."),
        tracos = listOf(NamedText("Cadência Coloratura", "Avança a ação em 25%.")),
        // Eidolons in order: index+1 == the eidolon number.
        eidolons = listOf(
            NamedText("Verdadeiras Palavras", "CRIT +18%."),
            NamedText("Trovões Mudos", "SPD +20%."),
        ),
    )

    // -------------------- wantedKit (routing) -------------------- //

    @Test
    fun `player shorthand routes to the right ability kind`() {
        assertEquals(KitAsk(setOf(SkillKind.ULTIMATE), null), KitAnswerService.wantedKit("o que a ult da robin faz?"))
        assertEquals(KitAsk(setOf(SkillKind.TALENT), null), KitAnswerService.wantedKit("qual o talento do welt?"))
        assertEquals(KitAsk(setOf(SkillKind.BASIC), null), KitAnswerService.wantedKit("o ataque básico da acheron é bom?"))
    }

    @Test
    fun `pericia suprema is only the ultimate, bare pericia is the skill`() {
        assertEquals(KitAsk(setOf(SkillKind.ULTIMATE), null), KitAnswerService.wantedKit("o que faz a perícia suprema da robin?"))
        assertEquals(KitAsk(setOf(SkillKind.SKILL), null), KitAnswerService.wantedKit("o que faz a perícia da robin?"))
    }

    @Test
    fun `eidolon shorthand carries the numbers, plural means all`() {
        assertEquals(KitAsk(emptySet(), setOf(1)), KitAnswerService.wantedKit("o que a e1 da acheron faz?"))
        assertEquals(KitAsk(emptySet(), setOf(2)), KitAnswerService.wantedKit("efeito do eidolon 2 da acheron"))
        assertEquals(KitAsk(emptySet(), emptySet<Int>()), KitAnswerService.wantedKit("quais os eidolons da acheron?"))
    }

    @Test
    fun `plural habilidades asks for the whole ability set`() {
        assertEquals(SkillKind.entries.toSet(), KitAnswerService.wantedKit("quais as habilidades da robin?")?.kinds)
    }

    @Test
    fun `trace and kit vocabulary route, with kit meaning the whole sheet`() {
        assertEquals(KitAsk(emptySet(), null, traces = true), KitAnswerService.wantedKit("qual o traço do welt?"))
        assertEquals(KitAsk(emptySet(), null, traces = true), KitAnswerService.wantedKit("o que faz a passiva da robin?"))
        assertEquals(
            KitAsk(SkillKind.entries.toSet(), emptySet(), traces = true, profile = true),
            KitAnswerService.wantedKit("qual o kit do phainon?"),
        )
    }

    @Test
    fun `build vocabulary excludes the kit path, and vice versa nothing routes twice`() {
        assertNull(KitAnswerService.wantedKit("qual a build e a ult do welt?"))
        assertNull(KitAnswerService.wantedKit("qual a build do phainon?"))
        assertNull(KitAnswerService.wantedKit("quem é a bronya?"))
        assertNull(KitAnswerService.wantedKit("qual a passiva do cone along the passing shore?"))
    }

    // -------------------- renderKit (template) -------------------- //

    @Test
    fun `renders the asked ability verbatim under a bold header`() {
        assertEquals(
            "**Robin — Perícia Suprema: Vox Harmonique, Opus Cosmique**\nEntra em Concerto.",
            KitAnswerService.renderKit(robin, KitAsk(setOf(SkillKind.ULTIMATE), null)),
        )
    }

    @Test
    fun `whole ability set renders in canonical kit order`() {
        val out = KitAnswerService.renderKit(robin, KitAsk(SkillKind.entries.toSet(), null))!!
        val order = listOf("— ATQ Básico:", "— Perícia:", "— Perícia Suprema:", "— Talento:", "— Técnica:")
        val positions = order.map { out.indexOf(it) }
        assertEquals(positions.sorted(), positions, "kit order broken in:\n$out")
    }

    @Test
    fun `eidolons filter by number and sort ascending when all are asked`() {
        assertEquals(
            "**Robin — Eidolon 1: Verdadeiras Palavras**\nCRIT +18%.",
            KitAnswerService.renderKit(robin, KitAsk(emptySet(), setOf(1))),
        )
        assertEquals(
            "**Robin — Eidolon 1: Verdadeiras Palavras**\nCRIT +18%.\n\n" +
                "**Robin — Eidolon 2: Trovões Mudos**\nSPD +20%.",
            KitAnswerService.renderKit(robin, KitAsk(emptySet(), emptySet())),
        )
    }

    @Test
    fun `full kit renders the whole sheet in fixed order with a lore-free profile header`() {
        val ask = KitAsk(SkillKind.entries.toSet(), emptySet(), traces = true, profile = true)
        val out = KitAnswerService.renderKit(robin, ask)!!
        val order = listOf(
            "**Robin — Honkai: Star Rail**", "Raridade: 5 estrelas",
            "— ATQ Básico:", "— Perícia Suprema:", "— Técnica:",
            "traço maior", "Eidolon 1", "Eidolon 2",
        )
        val positions = order.map { out.indexOf(it) }
        assertEquals(positions.sorted(), positions, "sheet order broken in:\n$out")
        // The profile's lore Descrição must not leak into the kit header.
        assertEquals(-1, out.indexOf("Uma cantora"))
    }

    @Test
    fun `full kit includes memoespirito and euforia only when the unit has them`() {
        val aglaea = PersonagemHsr(
            characterId = "1402", nome = "Aglaea", raridade = 5,
            periciaMemoespirito = NamedText("Armadilha de Espinhos", "Invoca o memoespírito."),
        )
        val out = KitAnswerService.renderKit(aglaea, KitAsk(SkillKind.entries.toSet(), emptySet(), traces = true, profile = true))!!
        assertTrue(out.contains("**Aglaea — Perícia do Memoespírito: Armadilha de Espinhos**"), out)
        // No euforia pair set → no euforia block.
        assertEquals(-1, out.indexOf("Euforia"))
    }

    @Test
    fun `traces render for a trace ask`() {
        assertEquals(
            "**Robin — traço maior: Cadência Coloratura**\nAvança a ação em 25%.",
            KitAnswerService.renderKit(robin, KitAsk(emptySet(), null, traces = true)),
        )
    }

    @Test
    fun `asked pieces the character lacks render null so the caller falls through`() {
        // Robin has only E1/E2 → an E3 ask has nothing.
        assertNull(KitAnswerService.renderKit(robin, KitAsk(emptySet(), setOf(3))))
        // A character with no ultimate.
        assertNull(KitAnswerService.renderKit(robin.copy(periciaSuprema = NamedText.EMPTY), KitAsk(setOf(SkillKind.ULTIMATE), null)))
    }
}
