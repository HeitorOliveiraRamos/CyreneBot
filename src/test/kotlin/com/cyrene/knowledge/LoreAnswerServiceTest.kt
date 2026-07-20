package com.cyrene.knowledge

import com.cyrene.hsr.NamedText
import com.cyrene.hsr.PersonagemHsr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the lore routing, whose whole point is the raw-vs-summarized split: a full-history ask
 * renders verbatim, a summary ask hands the same text to the voice pass instead of dumping it.
 */
class LoreAnswerServiceTest {

    private val himeko = PersonagemHsr(
        characterId = "1013",
        nome = "Himeko",
        descricao = "A navegadora do Expresso Astral.",
        detalhesPersonagem = "Engenheira e exploradora.",
        historias = listOf("Encontrou o Expresso.", "Consertou o motor.", null, null),
    )

    private fun ask(q: String) = LoreAnswerService.wantedLore(q)

    // -------------------- routing -------------------- //

    @Test
    fun `full history asks render raw`() {
        assertEquals(false, ask("me conta a história da himeko")?.summarize)
        assertEquals(false, ask("qual a lore do welt?")?.summarize)
        assertEquals(false, ask("me fala sobre o passado da acheron")?.summarize)
    }

    @Test
    fun `summary asks route to the voice pass`() {
        assertEquals(true, ask("me da um resumo da lore da himeko")?.summarize)
        assertEquals(true, ask("resume a história do welt")?.summarize)
        assertEquals(true, ask("me conta rapidinho a história da acheron")?.summarize)
    }

    @Test
    fun `quem e is an identity question and summarizes by default`() {
        assertEquals(true, ask("quem é a acheron?")?.summarize)
        assertEquals(true, ask("quem e o welt")?.summarize)
    }

    @Test
    fun `mechanics questions are not lore`() {
        assertNull(ask("qual a build da himeko"))
        assertNull(ask("me fala o kit da himeko"))
        assertNull(ask("o que a ult da robin faz?"))
        // "sobre" is broad, so the build gate is what keeps this out.
        assertNull(ask("me fala sobre a build da himeko"))
        assertNull(ask("qual o elemento da himeko"))
    }

    // -------------------- render -------------------- //

    @Test
    fun `renders every populated narrative column in reading order`() {
        val out = LoreAnswerService.render(himeko, "Himeko")!!
        assertTrue(out.startsWith("**Himeko**\nA navegadora do Expresso Astral."), out)
        assertTrue(out.contains("**Sobre Himeko**\nEngenheira e exploradora."), out)
        assertTrue(out.contains("**História — Parte 1**\nEncontrou o Expresso."), out)
        assertTrue(out.contains("**História — Parte 2**\nConsertou o motor."), out)
        // Parts 3 and 4 are null on this row and must not render as empty headers.
        assertFalse(out.contains("Parte 3"), out)
    }

    @Test
    fun `a row with no lore renders nothing so the caller falls through`() {
        val bare = PersonagemHsr(characterId = "9", nome = "Beta", talento = NamedText("x", "y"))
        assertNull(LoreAnswerService.render(bare, "Beta"))
    }
}
