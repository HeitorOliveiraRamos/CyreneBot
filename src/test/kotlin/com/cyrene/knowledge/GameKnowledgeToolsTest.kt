package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the pure matcher behind the name-anchored retrieval tier: which KB entity names a
 * free-form question is considered to literally mention.
 */
class GameKnowledgeToolsTest {

    private val names = listOf(
        "Acheron",
        "Along the Passing Shore",
        "Band of Sizzling Thunder",
        "Yu", // too short to judge — never matched
        null,
    )

    @Test
    fun `matchNames finds single and multi-word entity names, accent and case insensitive`() {
        assertEquals(listOf("Acheron"), GameKnowledgeTools.matchNames("quem é a ACHERON?", names))
        assertEquals(
            listOf("Along the Passing Shore"),
            GameKnowledgeTools.matchNames("qual o passivo do cone along the passing shore?", names),
        )
    }

    @Test
    fun `matchNames requires the whole name as whole words`() {
        // Substring of a longer word must not fire (the "cora/coração" contract).
        assertTrue(GameKnowledgeTools.matchNames("o acheronte da mitologia", names).isEmpty())
        // One token of a multi-word name is not the name.
        assertTrue(GameKnowledgeTools.matchNames("qual o melhor shore?", names).isEmpty())
    }

    @Test
    fun `matchNames prefers the SP form over the base character it contains`() {
        val forms = listOf("Robin", "Robin • Summeretto", "Dan Heng", "Dan Heng - Embebidor Lunae")
        // The SP name is stored with a separator nobody types — it must still win alone.
        assertEquals(
            listOf("Robin • Summeretto"),
            GameKnowledgeTools.matchNames("qual a build da robin summeretto?", forms),
        )
        assertEquals(
            listOf("Dan Heng - Embebidor Lunae"),
            GameKnowledgeTools.matchNames("kit do dan heng embebidor lunae", forms),
        )
        // The base name alone still anchors the base character.
        assertEquals(listOf("Robin"), GameKnowledgeTools.matchNames("quem é a robin?", forms))
        // Naming both fires both — the bare mention sits outside the SP name's span.
        assertEquals(
            listOf("Robin • Summeretto", "Robin"),
            GameKnowledgeTools.matchNames("compara a robin com a robin summeretto", forms),
        )
        // Same character ingested with different separators: both stored names match.
        assertEquals(
            listOf("Himeko - Nova", "Himeko • Nova"),
            GameKnowledgeTools.matchNames("quem é a himeko nova?", listOf("Himeko", "Himeko - Nova", "Himeko • Nova")),
        )
    }

    // -------------------- anchorNameGroups (shared resolver) -------------------- //

    // KB stores PT names only; the gazetteer carries EN/ES + variants. Himeko Nova is
    // deliberately KB-only (not in the gazetteer) to mirror the live data.
    private val kbNames = listOf("Herta", "A Herta", "Himeko", "Himeko - Nova", "Himeko • Nova", "Along the Passing Shore")
    private val gazetteer = listOf(
        HsrCharacter("1013", nameEn = "Herta", namePt = "Herta"),
        HsrCharacter("1401", nameEn = "The Herta", namePt = "A Herta"),
        HsrCharacter("1003", nameEn = "Himeko", namePt = "Himeko"),
    )

    private fun groups(query: String) =
        GameKnowledgeTools.anchorNameGroups(query, kbNames, gazetteer).map { it.toSet() }

    @Test
    fun `anchorNameGroups resolves an EN variant name to the character's PT KB docs`() {
        // "the herta" is EN — absent from the PT-only KB — yet must anchor A Herta, not base Herta.
        assertEquals(
            listOf(setOf("The Herta", "A Herta")),
            groups("qual a build da the herta"),
        )
    }

    @Test
    fun `anchorNameGroups anchors a gazetteer-missing variant from its own KB name`() {
        assertEquals(
            listOf(setOf("Himeko - Nova", "Himeko • Nova")),
            groups("qual a build da himeko nova"),
        )
        // Base forms still resolve to the base character alone.
        assertEquals(listOf(setOf("Himeko")), groups("build da himeko"))
        assertEquals(listOf(setOf("Herta")), groups("build da herta"))
    }

    @Test
    fun `anchorNameGroups keeps a non-character entity as its own group`() {
        assertEquals(
            listOf(setOf("Along the Passing Shore")),
            groups("qual o passivo do cone along the passing shore"),
        )
    }

    @Test
    fun `a variant alias re-introduces the base name — anchor must scan the raw query, not the enriched one`() {
        // "The Herta" is NOT a stored KB name (the KB carries the PT "A Herta"); the base
        // "Herta" IS. enrichQuery appends the variant's other-language aliases for cosine
        // recall, and "The Herta" contains whole-word "herta" — so anchoring on the ENRICHED
        // string wrongly pulls in the base character. This is why lookupHsr anchors on the raw
        // user query: the deterministic grounder passes it as anchorQuery. (live bug 2026-07-17)
        val kb = listOf("A Herta", "Herta")
        assertEquals(
            listOf("A Herta"),
            GameKnowledgeTools.matchNames("qual a build da a herta?", kb),
        )
        assertEquals(
            listOf("A Herta", "Herta"),
            GameKnowledgeTools.matchNames("qual a build da a herta? (The Herta)", kb),
        )
    }

    @Test
    fun `matchNames skips short names, nulls and caps the result`() {
        assertTrue(GameKnowledgeTools.matchNames("yu é bom?", names).isEmpty())
        val many = (1..6).map { "Personagem Número $it" }
        val matched = GameKnowledgeTools.matchNames(
            "fala de " + many.joinToString(", ") { it.lowercase() },
            many,
        )
        assertEquals(4, matched.size)
    }

    // -------------------- buildItemNames -------------------- //

    @Test
    fun `buildItemNames extracts relic, ornament and cone names, never team or stat lines`() {
        val doc = """
            Phainon — build recomendada (Honkai: Star Rail).
            Relíquias (4 peças, melhor primeiro): Wavestrider Captain; Champion of Streetwise Boxing
            Ornamento Planar (melhor primeiro): Arcadia of Woven Dreams; Rutilant Arena
            Cone de Luz (melhor primeiro): Thus Burns the Dawn
            Main stats: Corpo: Chance Crít., Pés: ATQ%
            Substats (prioridade): Chance Crít. > Dano Crít.
            Equipe recomendada: Phainon, Cerydra, Cyrene
        """.trimIndent()
        assertEquals(
            listOf(
                "Wavestrider Captain", "Champion of Streetwise Boxing",
                "Arcadia of Woven Dreams", "Rutilant Arena", "Thus Burns the Dawn",
            ),
            GameKnowledgeTools.buildItemNames(doc),
        )
        assertTrue(GameKnowledgeTools.buildItemNames("Acheron — habilidade: Octobolt Flash").isEmpty())
    }

    // -------------------- filterBySection -------------------- //

    private fun doc(category: String, content: String): Map<String, Any?> =
        mapOf("content" to content, "category" to category, "name" to "Acheron")

    private fun named(name: String, category: String, content: String): Map<String, Any?> =
        mapOf("content" to content, "category" to category, "name" to name)

    private val kit = listOf(
        doc("profile", "Acheron — personagem de Honkai: Star Rail."),
        doc("skill", "Acheron — habilidade: Octobolt Flash (Skill)"),
        doc("skill", "Acheron — habilidade: Slashed Dream Cries in Red (Ultimate)"),
        doc("trace", "Acheron — traço maior (Bonus Ability): The Abyss"),
        doc("eidolon", "Acheron — Eidolon 1: Red Oni"),
        doc("eidolon", "Acheron — Eidolon 2: Crimson Bloom"),
        doc("build", "Acheron — build recomendada (Honkai: Star Rail)."),
    )

    @Test
    fun `eidolon question with a number narrows to that single eidolon`() {
        val expected = listOf(kit[5])
        assertEquals(
            expected,
            GameKnowledgeTools.filterBySection(kit, "me retorna o efeito completo do eidolon 2 da acheron"),
        )
        assertEquals(expected, GameKnowledgeTools.filterBySection(kit, "o que faz a E2 da acheron?"))
    }

    @Test
    fun `eidolon question without a number keeps all eidolons`() {
        assertEquals(
            listOf(kit[4], kit[5]),
            GameKnowledgeTools.filterBySection(kit, "quais os eidolons da acheron?"),
        )
    }

    @Test
    fun `section cues filter by category, accent insensitive`() {
        assertEquals(
            listOf(kit[1], kit[2]),
            GameKnowledgeTools.filterBySection(kit, "qual a ultimate da acheron?"),
        )
        assertEquals(
            listOf(kit[3]),
            GameKnowledgeTools.filterBySection(kit, "quais os traços da acheron?"),
        )
    }

    @Test
    fun `relic, cone and team questions about a character pin the build doc`() {
        val build = listOf(kit[6])
        assertEquals(build, GameKnowledgeTools.filterBySection(kit, "qual a melhor relíquia pra acheron?"))
        assertEquals(build, GameKnowledgeTools.filterBySection(kit, "que cone usar na acheron?"))
        assertEquals(build, GameKnowledgeTools.filterBySection(kit, "que time montar com a acheron?"))
        // The same relic word against a relic-set entity keeps the relic_set doc.
        val relic = listOf(doc("relic_set", "Band of Sizzling Thunder — Conjunto de Relíquia"))
        assertEquals(
            relic,
            GameKnowledgeTools.filterBySection(relic, "efeito da relíquia band of sizzling thunder"),
        )
    }

    @Test
    fun `main stat and slot questions pin the build doc`() {
        val build = listOf(kit[6])
        // The exact live miss: "main stat" is two tokens, "corpo" is a slot word.
        assertEquals(
            build,
            GameKnowledgeTools.filterBySection(kit, "qual main stat que eu devo buscar no corpo do phainon?"),
        )
        assertEquals(build, GameKnowledgeTools.filterBySection(kit, "que stats rodar nos pés da acheron?"))
        assertEquals(build, GameKnowledgeTools.filterBySection(kit, "qual esfera usar na acheron?"))
    }

    // -------------------- pruneBuildLines -------------------- //

    private val weltBuild = mapOf<String, Any?>(
        "content" to """
            Welt — build recomendada (Honkai: Star Rail).
            Relíquias (4 peças, melhor primeiro): Wavestrider Captain
            Ornamento Planar (melhor primeiro): Rutilant Arena
            Cone de Luz (melhor primeiro): In the Name of the World
            Main stats: Corpo: Chance Crít., Pés: Velocidade
            Substats (prioridade): Chance Crít. > Dano Crít.
            Equipe recomendada: Welt, Himeko, Tribbie, Hyacine
        """.trimIndent(),
        "category" to "build",
        "name" to "Welt",
    )

    @Test
    fun `team question trims the build doc to the team line`() {
        // The live miss: given the whole doc, the voice dealt Welt's relics out
        // across the teammates as invented per-character builds.
        val pruned = GameKnowledgeTools.pruneBuildLines(
            listOf(weltBuild),
            "me fala a equipe recomendada do welt",
        )
        assertEquals(
            "Welt — build recomendada (Honkai: Star Rail).\n" +
                "Equipe recomendada: Welt, Himeko, Tribbie, Hyacine",
            pruned.single()["content"],
        )
    }

    @Test
    fun `facet questions keep only their lines and non-build docs pass through`() {
        val profile = mapOf<String, Any?>(
            "content" to "Welt — personagem.", "category" to "profile", "name" to "Welt",
        )
        val pruned = GameKnowledgeTools.pruneBuildLines(
            listOf(weltBuild, profile),
            "qual set e main stat pro welt?",
        )
        val content = pruned.first()["content"] as String
        assertTrue("Relíquias" in content && "Main stats" in content && "Substats" in content)
        assertFalse("Cone de Luz" in content || "Equipe recomendada" in content)
        assertEquals(profile, pruned[1])
    }

    @Test
    fun `no facet word, or a facet the doc lacks, leaves the build doc whole`() {
        assertEquals(listOf(weltBuild), GameKnowledgeTools.pruneBuildLines(listOf(weltBuild), "build do welt"))
        assertEquals(listOf(weltBuild), GameKnowledgeTools.pruneBuildLines(listOf(weltBuild), "quem é o welt?"))
        val noTeamLine = mapOf<String, Any?>(
            "content" to "Welt — build recomendada (Honkai: Star Rail).\n" +
                "Cone de Luz (melhor primeiro): In the Name of the World",
            "category" to "build",
            "name" to "Welt",
        )
        assertEquals(
            listOf(noTeamLine),
            GameKnowledgeTools.pruneBuildLines(listOf(noTeamLine), "melhor equipe do welt"),
        )
    }

    @Test
    fun `no cue, or a cue this entity has no docs for, leaves docs untouched`() {
        assertEquals(kit, GameKnowledgeTools.filterBySection(kit, "a acheron é boa?"))
        // A light cone has no eidolons — the cue must not turn a hit into a miss.
        val cone = listOf(doc("light_cone", "Along the Passing Shore — Cone de Luz"))
        assertEquals(
            cone,
            GameKnowledgeTools.filterBySection(cone, "eidolon 2 do cone along the passing shore"),
        )
    }

    // -------------------- mergeHits -------------------- //

    @Test
    fun `mergeHits drops other-character vector docs when the query names one`() {
        val cyreneBuild = named("Cyrene", "build", "Cyrene — build recomendada")
        val vector = listOf(
            named("Cipher", "build", "Cipher — build recomendada"),
            named("Castorice", "build", "Castorice — build recomendada"),
            cyreneBuild, // semantic search may also surface the real one
        )
        assertEquals(
            listOf(cyreneBuild),
            GameKnowledgeTools.mergeHits(listOf(cyreneBuild), vector, "qual set usar na cyrene?"),
        )
    }

    @Test
    fun `mergeHits scopes same-entity vector fill to the asked section`() {
        val e2 = named("Acheron", "eidolon", "Acheron — Eidolon 2: Mute Thunder")
        val vector = listOf(
            named("Acheron", "skill", "Acheron — habilidade: Octobolt Flash"),
            named("Acheron", "eidolon", "Acheron — Eidolon 1: Silenced Sky"),
        )
        // "eidolon 2" keeps only E2, even though the vector fill is all same-character docs.
        assertEquals(
            listOf(e2),
            GameKnowledgeTools.mergeHits(listOf(e2), vector, "efeito do eidolon 2 da acheron"),
        )
    }

    @Test
    fun `mergeHits leaves the vector tier alone when nothing was named`() {
        val vector = listOf(
            named("Cipher", "build", "x"),
            named("Castorice", "build", "y"),
        )
        assertEquals(vector, GameKnowledgeTools.mergeHits(emptyList(), vector, "melhor build quantum?"))
    }
}
