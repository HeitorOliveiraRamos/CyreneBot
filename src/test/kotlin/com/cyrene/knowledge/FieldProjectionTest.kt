package com.cyrene.knowledge

import com.cyrene.hsr.NamedText
import com.cyrene.hsr.PersonagemHsr
import com.cyrene.knowledge.ItemAnswerService.PieceField
import com.cyrene.knowledge.KitAnswerService.CharField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the hand-mapped field vocabulary: asking for ONE column returns that column and nothing
 * else, on both the character tables and the relic/ornament/cone ones.
 */
class FieldProjectionTest {

    private val himeko = PersonagemHsr(
        characterId = "1013",
        nome = "Himeko",
        raridade = 5,
        caminho = "A Erudição",
        elemento = "Fogo",
        faccao = "Expresso Astral",
        descricao = "A navegadora do Expresso Astral.",
        talento = NamedText("Vitória Vitoriosa", "Dano extra."),
    )

    // -------------------- character fields -------------------- //

    @Test
    fun `field words route to their column`() {
        assertEquals(setOf(CharField.DESCRICAO), KitAnswerService.wantedKit("me fala a descrição da himeko")?.fields)
        assertEquals(setOf(CharField.FACCAO), KitAnswerService.wantedKit("qual a facção do welt?")?.fields)
        assertEquals(setOf(CharField.ELEMENTO), KitAnswerService.wantedKit("qual o elemento da himeko")?.fields)
        assertEquals(setOf(CharField.CAMINHO), KitAnswerService.wantedKit("qual o caminho da acheron")?.fields)
        assertEquals(setOf(CharField.RARIDADE), KitAnswerService.wantedKit("quantas estrelas a hanya tem")?.fields)
    }

    @Test
    fun `renders only the asked field`() {
        val ask = KitAnswerService.wantedKit("me fala a descrição da himeko")!!
        val out = KitAnswerService.renderKit(himeko, ask, "Himeko")!!
        assertEquals("**Himeko — Descrição**\nA navegadora do Expresso Astral.", out)
        // Nothing else from the row leaks in.
        assertFalse(out.contains("Erudição"), out)
        assertFalse(out.contains("Vitória"), out)
    }

    @Test
    fun `element ask returns the element alone`() {
        val ask = KitAnswerService.wantedKit("qual o elemento da himeko")!!
        assertEquals("**Himeko — Elemento**\nFogo", KitAnswerService.renderKit(himeko, ask, "Himeko"))
    }

    @Test
    fun `two fields in one question render both`() {
        val ask = KitAnswerService.wantedKit("qual o elemento e o caminho da himeko")!!
        val out = KitAnswerService.renderKit(himeko, ask, "Himeko")!!
        assertTrue(out.contains("**Himeko — Caminho**\nA Erudição"), out)
        assertTrue(out.contains("**Himeko — Elemento**\nFogo"), out)
    }

    // -------------------- relic / ornament pieces -------------------- //

    @Test
    fun `piece words route to their piece`() {
        assertEquals(setOf(PieceField.PES), ItemAnswerService.wantedPieces("qual o nome dos pés do set Campeã de Boxe de Rua"))
        assertEquals(setOf(PieceField.CABECA), ItemAnswerService.wantedPieces("o capacete do set X"))
        assertEquals(setOf(PieceField.ESFERA), ItemAnswerService.wantedPieces("qual a bola do Izumo"))
        assertEquals(setOf(PieceField.CORDA), ItemAnswerService.wantedPieces("a corda do Salsola"))
        assertEquals(emptySet(), ItemAnswerService.wantedPieces("qual o efeito de 4 peças do set X"))
    }

    @Test
    fun `piece block renders the piece name and its flavour text`() {
        val out = ItemAnswerService.pieceBlock(
            "Campeã de Boxe de Rua",
            PieceField.PES,
            NamedText("Botas de Combate da Campeã", "Calçados gastos de tanto treino."),
        )
        assertEquals(
            "**Campeã de Boxe de Rua — Pés: Botas de Combate da Campeã**\nCalçados gastos de tanto treino.",
            out,
        )
    }

    @Test
    fun `a piece with no flavour text still renders its header`() {
        val out = ItemAnswerService.pieceBlock("Set X", PieceField.MAOS, NamedText("Luvas", null))
        assertEquals("**Set X — Mãos: Luvas**", out)
    }
}
