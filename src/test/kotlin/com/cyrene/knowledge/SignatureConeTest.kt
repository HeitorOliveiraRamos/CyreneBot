package com.cyrene.knowledge

import com.cyrene.hsr.ConeDeLuz
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the signature-cone routing: "o cone do X" is the cone assigned to him
 * (`id_personagem_hsr_atribuido`), while anything comparative or plural stays the ranked
 * recommendation list the build row already produced.
 */
class SignatureConeTest {

    private val cone = ConeDeLuz(
        nome = "Aonde o Vento Sopra",
        caminho = "A Destruição",
        raridade = 5,
        efeitoNome = "Chama Devoradora",
        efeitoDescricao = "Aumenta o CRIT DMG em 36%.",
        descricao = "Uma lâmina forjada em cinzas.",
    )

    @Test
    fun `possessive singular asks for the signature cone`() {
        assertTrue(BuildAnswerService.wantsSignatureCone("qual o efeito do cone do phainon?"))
        assertTrue(BuildAnswerService.wantsSignatureCone("me fala o cone da acheron"))
        assertTrue(BuildAnswerService.wantsSignatureCone("cone de luz do welt"))
        assertTrue(BuildAnswerService.wantsSignatureCone("qual o cone assinatura da robin"))
    }

    @Test
    fun `comparative or plural stays the recommendation list`() {
        assertFalse(BuildAnswerService.wantsSignatureCone("qual o melhor cone pra acheron?"))
        assertFalse(BuildAnswerService.wantsSignatureCone("quais os cones da robin"))
        assertFalse(BuildAnswerService.wantsSignatureCone("me da opções de cone pro welt"))
        // No possessive binding at all — a bare mention isn't a signature ask.
        assertFalse(BuildAnswerService.wantsSignatureCone("esse cone é bom?"))
    }

    @Test
    fun `renders name, stat line and effect`() {
        val out = BuildAnswerService.renderSignatureCone("Phainon", cone)
        assertTrue(out.contains("**Phainon — Cone de Luz assinatura**"), out)
        assertTrue(out.contains("**Aonde o Vento Sopra** (5★ · A Destruição)"), out)
        assertTrue(out.contains("Efeito (Chama Devoradora): Aumenta o CRIT DMG em 36%."), out)
    }

    @Test
    fun `a cone with no effect text still renders its header`() {
        val bare = ConeDeLuz(nome = "Cone X", caminho = null, raridade = null)
        val out = BuildAnswerService.renderSignatureCone("Alguém", bare)
        assertTrue(out.contains("**Cone X**"), out)
        assertFalse(out.contains("Efeito"), out)
    }
}
