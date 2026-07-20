package com.cyrene.knowledge

import com.cyrene.hsr.NamedText
import com.cyrene.hsr.PersonagemHsr
import com.cyrene.knowledge.KitAnswerService.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the memoespírito/euforia routing. Before this, "o que o talento do memoespírito da hyacine
 * faz?" cued the bare "talento" token and confidently answered with her REGULAR Talento — the
 * question routed, matched a character, and returned the wrong column.
 */
class KitMemoEuphoriaTest {

    private val hyacine = PersonagemHsr(
        characterId = "1409",
        nome = "Hyacine",
        caminho = "A Recordação",
        talento = NamedText("Pequeno Vento Feliz", "Cura o time."),
        pericia = NamedText("Chuva Passageira", "Cura um aliado."),
        periciaMemoespirito = NamedText("Ritmo Alado", "O memoespírito ataca."),
        talentoMemoespirito = NamedText("Céu Sereno", "Cura enquanto o memoespírito estiver em campo."),
    )

    private val tbEuphoria = PersonagemHsr(
        characterId = "8009",
        nome = "Desbravador",
        caminho = "A Euforia",
        pericia = NamedText("Golpe Direto", "Dano em um alvo."),
        periciaEuforia = NamedText("Coringa", "Muda a carta ativa."),
    )

    private fun ask(q: String) = KitAnswerService.wantedKit(q)

    // -------------------- the reported bug -------------------- //

    @Test
    fun `memosprite talent does not cue the regular talent`() {
        assertEquals(setOf(SkillKind.MEMO_TALENT), ask("o que o talento do memoespírito da hyacine faz?")?.kinds)
    }

    @Test
    fun `memosprite skill routes to the memosprite column`() {
        assertEquals(setOf(SkillKind.MEMO_SKILL), ask("qual a perícia do memoespírito da hyacine?")?.kinds)
        assertEquals(setOf(SkillKind.MEMO_SKILL), ask("a habilidade do memosprite da castorice")?.kinds)
    }

    @Test
    fun `euphoria skill routes to the euphoria column`() {
        assertEquals(setOf(SkillKind.EUPHORIA), ask("o que a perícia da euforia faz?")?.kinds)
    }

    @Test
    fun `bare mention with no ability word asks for the whole memosprite`() {
        assertEquals(
            setOf(SkillKind.MEMO_SKILL, SkillKind.MEMO_TALENT),
            ask("o que o memoespírito da hyacine faz?")?.kinds,
        )
    }

    // -------------------- the Path must not be mistaken for the ability -------------------- //

    @Test
    fun `euforia as a Path leaves a real ability cue alone`() {
        // "perícia" here belongs to the character, and "da euforia" identifies WHICH Trailblazer.
        // Binding it to the euphoria ability would answer the wrong column.
        assertEquals(setOf(SkillKind.SKILL), ask("a perícia da desbravadora da euforia")?.kinds)
    }

    @Test
    fun `full kit still includes memosprite and euphoria`() {
        val kinds = ask("me fala o kit da hyacine")?.kinds.orEmpty()
        assertTrue(SkillKind.MEMO_SKILL in kinds && SkillKind.MEMO_TALENT in kinds && SkillKind.EUPHORIA in kinds)
    }

    // -------------------- render -------------------- //

    @Test
    fun `renders only the asked memosprite block`() {
        val out = KitAnswerService.renderKit(hyacine, ask("o talento do memoespírito da hyacine")!!, "Hyacine")!!
        assertTrue(out.contains("Talento do Memoespírito: Céu Sereno"), out)
        assertTrue(out.contains("Cura enquanto o memoespírito"), out)
        // The regular talent is the thing that used to leak out here.
        assertFalse(out.contains("Pequeno Vento Feliz"), out)
    }

    @Test
    fun `a character without a memosprite renders nothing rather than an empty block`() {
        val plain = PersonagemHsr(characterId = "1", nome = "Welt", talento = NamedText("X", "Y"))
        assertEquals(null, KitAnswerService.renderKit(plain, ask("o memoespírito do welt")!!, "Welt"))
    }

    @Test
    fun `euphoria renders for the euphoria trailblazer`() {
        val out = KitAnswerService.renderKit(tbEuphoria, ask("qual a perícia da euforia?")!!, "Desbravador (A Euforia)")!!
        assertTrue(out.contains("Desbravador (A Euforia) — Perícia da Euforia: Coringa"), out)
        assertFalse(out.contains("Golpe Direto"), out)
    }
}
