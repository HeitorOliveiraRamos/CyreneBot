package com.cyrene.knowledge

import com.cyrene.ai.OllamaAiService
import com.cyrene.hsr.HsrCharacterService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check of the deterministic answer paths against the REAL `personagem_hsr` /
 * `reliquias` / `ornamentos_planos` / `cones_de_luz` / `builds` data. Unit tests cover the pure
 * routing and render functions with fixtures; this is what catches the things a fixture cannot —
 * a column that's null across the whole table, a name that doesn't resolve against the live
 * gazetteer, a cue that matches nothing real.
 *
 * Opt-in (needs Postgres + the populated V17 tables), same gating style as
 * [com.cyrene.ai.LlmEvalTest]:
 *
 *     RUN_DB_TESTS=true mvn test -Dtest=AnswerPathsLiveTest
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class AnswerPathsLiveTest {

    @Autowired private lateinit var kit: KitAnswerService
    @Autowired private lateinit var build: BuildAnswerService
    @Autowired private lateinit var item: ItemAnswerService
    @Autowired private lateinit var lore: LoreAnswerService
    @Autowired private lateinit var roster: RosterAnswerService
    @Autowired private lateinit var characters: HsrCharacterService

    /** Chain order must mirror [com.cyrene.ai.OllamaAiService.runKnowledgePipeline] exactly. */
    private fun answer(q: String): String? =
        build.answer(q) ?: kit.answer(q) ?: lore.answer(q) ?: item.answer(q) ?: roster.answer(q)

    /** The chain as [com.cyrene.ai.OllamaAiService] runs it, so ordering bugs surface here too. */
    private fun assertAnswers(query: String, vararg mustContain: String) {
        val out = assertNotNull(answer(query), "no deterministic answer for: $query")
        mustContain.forEach { assertTrue(out.contains(it, ignoreCase = true), "«$it» missing from answer to «$query»:\n$out") }
    }

    // -------------------- Phase 1: variants -------------------- //

    @Test
    fun `march 7th hunt resolves to the hunt row only`() {
        val out = assertNotNull(answer("me fala o kit da março de caça"))
        // Assert on the block HEADERS, not the body: ability descriptions legitimately name other
        // Paths (her Perícia lists "A Harmonia, A Inexistência, A Preservação, A Abundância"), so
        // a whole-text search for the other variant's Path is guaranteed to false-positive.
        val headers = out.lines().filter { it.startsWith("**") }
        assertTrue(headers.isNotEmpty(), out)
        assertTrue(headers.all { it.contains("A Caça") }, "the Preservation March leaked in:\n$out")
    }

    @Test
    fun `display names disambiguate the shared ones`() {
        assertTrue(characters.displayName("1224").contains("A Caça"))
        assertTrue(characters.displayName("1001").contains("A Preservação"))
        // Unambiguous names stay bare.
        assertTrue(!characters.displayName("1308").contains("("), characters.displayName("1308"))
    }

    // -------------------- Phase 2: memosprite / euphoria -------------------- //

    @Test
    fun `memosprite talent answers with the memosprite column`() {
        assertAnswers("o que o talento do memoespírito da hyacine faz?", "Memoespírito")
    }

    // -------------------- Phase 3: signature cone -------------------- //

    @Test
    fun `signature cone answers with one cone`() {
        assertAnswers("qual o efeito do cone do phainon?", "Cone de Luz assinatura")
    }

    @Test
    fun `comparative cone question still returns the ranked list`() {
        assertAnswers("qual o melhor cone pra acheron?", "build recomendada", "Cone de Luz")
    }

    // -------------------- Phase 4: lore -------------------- //

    @Test
    fun `full history renders raw`() {
        assertAnswers("me conta a história da himeko", "História")
    }

    @Test
    fun `summary asks defer to the voice pass instead of rendering`() {
        // answer() must decline so KnowledgeGrounder's lore tier picks it up.
        assertTrue(lore.answer("me da um resumo da lore da himeko") == null)
        assertNotNull(lore.context("me da um resumo da lore da himeko"))
    }

    // -------------------- Phase 5: field projection -------------------- //

    @Test
    fun `single field asks return only that field`() {
        val out = assertNotNull(answer("me fala a descrição da himeko"))
        assertTrue(out.contains("Descrição"), out)
        assertTrue(!out.contains("Eidolon"), "whole kit leaked into a field ask:\n$out")
    }

    @Test
    fun `relic piece asks return that piece`() {
        val sets = listOf("Campeã de Boxe de Rua", "Guarda do Ermo de Sal")
        val hit = sets.firstNotNullOfOrNull { answer("qual o nome dos pés do set $it") }
        assertNotNull(hit, "no piece answer for any of $sets")
        assertTrue(hit.contains("Pés"), hit)
    }

    // -------------------- Phase 6: roster -------------------- //

    @Test
    fun `roster filter lists ice characters`() {
        val out = assertNotNull(answer("me fala 5 personagens de gelo aleatórios"))
        assertTrue(out.contains("Gelo"), out)
        // Header + exactly 5 entries.
        assertTrue(out.lines().count { it.startsWith("- ") } == 5, out)
    }

    @Test
    fun `roster count answers with a number`() {
        val out = assertNotNull(answer("quantos personagens de vento tem?"))
        assertTrue(Regex("\\*\\*.+\\*\\*: \\d+").containsMatchIn(out), out)
    }

    // -------------------- Phase 7: fuzzy -------------------- //

    @Test
    fun `partial and misspelled names resolve against the real roster`() {
        assertTrue(characters.findInText("build do embebidor").isNotEmpty())
        assertTrue(characters.findInText("qual a build da acheronn").isNotEmpty())
    }

    // -------------------- regressions from the 2026-07-19 live test round -------------------- //

    @Test
    fun `character lore is not hijacked by an item whose name contains the cue word`() {
        // The cone "A Próxima Página da História" owns "história" as a distinctive token, and the
        // item path ran before lore — so this question was answered with that cone's flavour text.
        val out = assertNotNull(answer("me conta a história da himeko"))
        assertTrue(out.contains("Himeko"), out)
        assertTrue(!out.contains("Página da História"), "an item hijacked the lore answer:\n$out")
    }

    @Test
    fun `an item lore question still reaches the item path after the reorder`() {
        // The other side of the reorder: lore runs first now, but it needs a named CHARACTER, so
        // a question naming a cone must fall through to the item path — including when the cone's
        // own name contains the cue word.
        val out = assertNotNull(answer("qual a descrição do cone A Próxima Página da História"))
        assertTrue(out.contains("Página da História"), out)
    }

    @Test
    fun `a misspelled path still selects the right variant`() {
        // "recordaçãp" named no Path, so this fell back to all five Trailblazers and rendered the
        // Destruction one.
        val out = assertNotNull(answer("me da a build do desbravador da recordaçãp"))
        assertTrue(out.contains("A Recordação"), out)
        assertTrue(!out.contains("A Destruição"), "wrong Trailblazer variant:\n$out")
    }

    @Test
    fun `faction membership counts characters, not table rows`() {
        val out = assertNotNull(answer("quantos membros tem na facção Expresso Astral?"))
        val n = assertNotNull(Regex("\\*\\*.*Expresso Astral\\*\\*: (\\d+)").find(out)).groupValues[1].toInt()
        // 15 rows, but ten of them are Trailblazer Path/gender variants — a player counts 7.
        assertEquals(7, n, out)
        assertTrue(out.contains("Desbravador — 5 formas"), "variants should be named as forms:\n$out")
        assertTrue(out.contains("Himeko"), out)
    }

    @Test
    fun `roster filters stack, including 3-star rarity`() {
        val out = assertNotNull(answer("me da 5 cones de luz da destruição 3 estrelas"))
        assertTrue(out.contains("3★") || out.contains("Cones de Luz 3★"), out)
        assertTrue(out.lines().count { it.startsWith("- ") } in 1..5, out)
    }

    @Test
    fun `item totals count from the table instead of going to the web`() {
        assertTrue(Regex("\\*\\*Relíquias\\*\\*: \\d+").containsMatchIn(assertNotNull(answer("quantas relíquias tem no total?"))))
        assertTrue(Regex("\\*\\*Cones de Luz\\*\\*: \\d+").containsMatchIn(assertNotNull(answer("quantos cones de luz existem no total?"))))
    }

    /**
     * The gap the unit tests can't close: [IntentGateRoutingTest] exercises the gate with a STUB
     * table predicate, so it proves the gate logic but not that the real parser agrees with it.
     * This wires the actual [RosterAnswerService] into the actual gate over the real roster —
     * which is precisely where the 2026-07-19 failures lived (every service was correct; the
     * question never reached them).
     */
    @Test
    fun `the real gate routes these to knowledge before any service runs`() {
        listOf(
            "me conta a história da himeko",
            "quantos membros tem na facção Expresso Astral?",
            "quantas relíquias tem no total?",
            "me da 5 cones de luz da destruição 3 estrelas",
            "qual a facção do welt?",
        ).forEach { q ->
            assertEquals(
                OllamaAiService.Intent.KNOWLEDGE,
                OllamaAiService.gazetteerFastPath(
                    q,
                    mentionsCharacter = { characters.findInText(it).isNotEmpty() },
                    isTableQuestion = { roster.isTableQuestion(it) },
                ),
                "gate did not route to KNOWLEDGE: $q",
            )
        }
    }

    @Test
    fun `the real gate leaves server and banter questions alone`() {
        listOf(
            "quantos membros tem no servidor?",
            "bane o <@123> por spam",
            "me conta uma história aí",
            "será que a acheron me ama?",
        ).forEach { q ->
            assertEquals(
                null,
                OllamaAiService.gazetteerFastPath(
                    q,
                    mentionsCharacter = { characters.findInText(it).isNotEmpty() },
                    isTableQuestion = { roster.isTableQuestion(it) },
                ),
                "gate wrongly hard-routed: $q",
            )
        }
    }

    @Test
    fun `ordinary chat resolves to nobody`() {
        listOf("alguem ai quer jogar hoje a noite", "boa noite gente", "tem vaga no time de voces")
            .forEach { assertTrue(characters.findInText(it).isEmpty(), "false positive on: $it") }
    }
}
