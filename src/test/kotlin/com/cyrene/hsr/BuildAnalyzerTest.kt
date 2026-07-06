package com.cyrene.hsr

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the deterministic scoring contract behind `/build`: the StarRailScore formula
 * (main `(lvl+1)/16 × weight`, subs `Σ(count + step×0.1)×weight / max`, SRS-M = √mean ×10),
 * the judgment flags derived from weights, and the two pure JSON parsers that feed it.
 */
class BuildAnalyzerTest {

    private val weights = ScoreWeights.CharWeights(
        main = mapOf(3 to mapOf("CriticalChanceBase" to 1.0)),
        weight = mapOf("CriticalDamageBase" to 1.0, "HPDelta" to 0.0, "SpeedDelta" to 1.0),
        max = 11.0,
    )

    private fun relic(
        slot: Int = 3,
        level: Int = 15,
        mainType: String = "CriticalChanceBase",
        subs: List<MihomoSubAffix> = emptyList(),
    ) = MihomoRelic(
        name = "Peça de Teste",
        slot = slot,
        setName = "Conjunto de Teste",
        rarity = 5,
        level = level,
        mainAffix = MihomoAffix(type = mainType, name = mainType, display = "x"),
        subAffixes = subs,
    )

    @Test
    fun `scoreRelic follows the StarRailScore formula exactly`() {
        // main: (15+1)/16 × 1.0 = 1.0; subs: (5 + 5×0.1)×1.0 = 5.5, /11 = 0.5
        // SRS-N = (1.0 + 0.5)/2 = 0.75 → SRS-M = √0.75 ×10 = 8.660…
        val r = BuildAnalyzer.scoreRelic(
            relic(subs = listOf(MihomoSubAffix("CriticalDamageBase", "Dano Crít.", "x", count = 5, step = 5))),
            weights,
        )
        assertEquals(8.660, r.score, 0.001)
        assertEquals("SS", r.rank)
        assertTrue(r.deadSubs.isEmpty())
    }

    @Test
    fun `scoreRelic flags zero-weight substats as dead and a zero-weight main as wrong`() {
        val r = BuildAnalyzer.scoreRelic(
            relic(
                mainType = "AttackAddedRatio", // not in this character's slot-3 main table
                subs = listOf(
                    MihomoSubAffix("HPDelta", "HP", "x", count = 3, step = 0),
                    MihomoSubAffix("SpeedDelta", "Velocidade", "x", count = 2, step = 1),
                ),
            ),
            weights,
        )
        assertEquals(0.0, r.mainWeight)
        assertEquals(listOf("HP"), r.deadSubs)
        // subs: (2 + 0.1)×1.0 = 2.1 → /11 = 0.1909; main 0 → SRS-N 0.0954 → √ ×10 = 3.089
        assertEquals(3.089, r.score, 0.001)
        assertEquals("D", r.rank)
    }

    @Test
    fun `analyze averages over all six slots and reports missing ones`() {
        val character = MihomoCharacter(
            id = "1308", name = "Acheron", level = 80, eidolon = 0,
            pathName = "Niilismo", elementName = "Raio",
            lightCone = null,
            relics = listOf(relic(subs = listOf(MihomoSubAffix("CriticalDamageBase", "Dano Crít.", "x", 5, 5)))),
            relicSets = emptyList(),
        )
        val report = BuildAnalyzer.analyze(character, weights)
        assertEquals(listOf(1, 2, 4, 5, 6), report.missingSlots)
        // One 8.660 piece over six slots — empty slots drag the build down on purpose.
        assertEquals(8.660 / 6.0, report.totalScore, 0.001)
        assertEquals(3, report.weakest?.slot)
    }

    private fun scored(
        slot: Int,
        score: Double,
        level: Int = 15,
        mainWeight: Double = 1.0,
        mainName: String = "x",
    ) = BuildAnalyzer.RelicScore(
        slot = slot, level = level, setName = "Set", mainName = mainName,
        mainWeight = mainWeight, score = score, rank = BuildAnalyzer.rank(score), deadSubs = emptyList(),
    )

    /** Realistic ruler shape: mains for all six slots (like the real score.json). */
    private val fullWeights = ScoreWeights.CharWeights(
        main = mapOf(
            1 to mapOf("HPDelta" to 1.0),
            2 to mapOf("AttackDelta" to 1.0),
            3 to mapOf("CriticalChanceBase" to 1.0, "HPAddedRatio" to 0.5),
            4 to mapOf("SpeedDelta" to 1.0),
            5 to mapOf("AttackAddedRatio" to 1.0),
            6 to mapOf("SPRatioBase" to 1.0),
        ),
        weight = mapOf("CriticalDamageBase" to 1.0, "SpeedDelta" to 1.0),
        max = 11.0,
    )

    @Test
    fun `farmPlan puts empty slots first and caps the list`() {
        val plan = BuildAnalyzer.farmPlan(
            relics = listOf(scored(slot = 3, score = 5.0, mainWeight = 0.5)),
            missingSlots = listOf(1, 2, 4, 5, 6),
            weights = fullWeights,
        )
        assertEquals(3, plan.size)
        assertTrue(plan.all { "vazio" in it }, plan.toString())
    }

    @Test
    fun `farmPlan flags a wrong main with the ideal replacements, heaviest first`() {
        val plan = BuildAnalyzer.farmPlan(
            relics = listOf(scored(slot = 3, score = 3.0, mainWeight = 0.0, mainName = "ATQ%")),
            missingSlots = emptyList(),
            weights = fullWeights,
        )
        val line = plan.single()
        assertTrue("não serve" in line && "ATQ%" in line, line)
        assertTrue("Chance Crít./PV%" in line, line)
    }

    @Test
    fun `farmPlan suggests leveling an underleveled piece before anything else`() {
        val plan = BuildAnalyzer.farmPlan(
            relics = listOf(scored(slot = 4, score = 5.3, level = 8)),
            missingSlots = emptyList(),
            weights = fullWeights,
        )
        assertTrue("+8 para +15" in plan.single(), plan.single())
    }

    @Test
    fun `farmPlan suggests a substat refarm for a sub-par piece whose main is acceptable`() {
        val plan = BuildAnalyzer.farmPlan(
            relics = listOf(scored(slot = 3, score = 5.0, mainWeight = 0.5)),
            missingSlots = emptyList(),
            weights = fullWeights,
        )
        val line = plan.single()
        assertTrue("refarm" in line, line)
        assertTrue("Dano Crít. > Velocidade" in line, line)
    }

    @Test
    fun `farmPlan skips wrong-main advice when the ruler has no ideal mains for the slot`() {
        val noSlotMains = ScoreWeights.CharWeights(
            main = mapOf(3 to mapOf("CriticalChanceBase" to 1.0)),
            weight = mapOf("CriticalDamageBase" to 1.0),
            max = 11.0,
        )
        val plan = BuildAnalyzer.farmPlan(
            relics = listOf(scored(slot = 5, score = 5.0, mainWeight = 0.0)),
            missingSlots = emptyList(),
            weights = noSlotMains,
        )
        // Falls through to the refarm suggestion instead of "troque por —".
        assertTrue(plan.single().let { "refarm" in it && "troque" !in it }, plan.toString())
    }

    @Test
    fun `farmPlan is empty when nothing is worth farming, and render falls back to the weakest line`() {
        val relics = (1..6).map { scored(slot = it, score = 8.0) }
        assertTrue(BuildAnalyzer.farmPlan(relics, emptyList(), fullWeights).isEmpty())

        val report = BuildAnalyzer.BuildReport(
            relics = relics, missingSlots = emptyList(), totalScore = 8.0, totalRank = "S",
            weakest = relics.first(), farmPlan = emptyList(),
        )
        val character = MihomoCharacter(
            id = "1308", name = "Acheron", level = 80, eidolon = 0,
            pathName = "Niilismo", elementName = "Raio", lightCone = null,
            relics = emptyList(), relicSets = emptyList(),
        )
        val out = BuildAnalyzer.render(character, report)
        assertTrue("Peça mais fraca" in out, out)
        assertTrue("Próximo farm" !in out, out)
    }

    @Test
    fun `render shows the farm plan section when the report carries one`() {
        val report = BuildAnalyzer.BuildReport(
            relics = emptyList(), missingSlots = emptyList(), totalScore = 5.0, totalRank = "C",
            weakest = null, farmPlan = listOf("**Corpo**: subir de +8 para +15 já melhora a nota."),
        )
        val character = MihomoCharacter(
            id = "1308", name = "Acheron", level = 80, eidolon = 0,
            pathName = "Niilismo", elementName = "Raio", lightCone = null,
            relics = emptyList(), relicSets = emptyList(),
        )
        val out = BuildAnalyzer.render(character, report)
        assertTrue("**Próximo farm:**" in out, out)
        assertTrue("+8 para +15" in out, out)
    }

    @Test
    fun `rank thresholds map scores to the expected letters`() {
        assertEquals("SS", BuildAnalyzer.rank(8.5))
        assertEquals("S", BuildAnalyzer.rank(8.0))
        assertEquals("A", BuildAnalyzer.rank(7.0))
        assertEquals("B", BuildAnalyzer.rank(6.0))
        assertEquals("C", BuildAnalyzer.rank(4.5))
        assertEquals("D", BuildAnalyzer.rank(4.4))
    }

    @Test
    fun `MihomoParser maps the parsed API shape including 2pc+4pc set dedupe`() {
        val json = """
            {"player": {"nickname": "Heitor"},
             "characters": [{
               "id": "1308", "name": "Acheron", "level": 80, "rank": 1,
               "path": {"name": "Niilismo"}, "element": {"name": "Raio"},
               "light_cone": {"name": "Cone X", "rank": 2, "level": 80},
               "relics": [{
                 "name": "Chapéu", "type": 1, "set_name": "Set A", "rarity": 5, "level": 15,
                 "main_affix": {"type": "HPDelta", "name": "HP", "display": "705"},
                 "sub_affix": [{"type": "SpeedDelta", "name": "Velocidade", "display": "2.0", "count": 1, "step": 0}]
               }],
               "relic_sets": [
                 {"name": "Set A", "num": 2}, {"name": "Set A", "num": 4}, {"name": "Set B", "num": 2}
               ]
             }]}
        """.trimIndent()
        val profile = MihomoParser.parse(ObjectMapper().readTree(json))
        assertEquals("Heitor", profile.nickname)
        val c = profile.characters.single()
        assertEquals("Acheron", c.name)
        assertEquals(1, c.eidolon)
        assertEquals(2, c.lightCone?.superimposition)
        val r = c.relics.single()
        assertEquals(1, r.slot)
        assertEquals("HPDelta", r.mainAffix?.type)
        assertEquals(1, r.subAffixes.single().count)
        // 2pç+4pç of the same set collapse to the 4pç entry.
        assertEquals(listOf("Set A" to 4, "Set B" to 2), c.relicSets.map { it.name to it.pieces })
    }

    @Test
    fun `ScoreWeights parse maps score json and skips entries without a max`() {
        val json = """
            {"1308": {"main": {"3": {"CriticalChanceBase": 1.0}}, "weight": {"SpeedDelta": 1.0}, "max": 9.0},
             "9999": {"main": {}, "weight": {}, "max": 0}}
        """.trimIndent()
        val table = ScoreWeights.parse(ObjectMapper().readTree(json))
        assertEquals(setOf("1308"), table.keys)
        assertEquals(1.0, table["1308"]!!.main[3]!!["CriticalChanceBase"])
        assertEquals(9.0, table["1308"]!!.max)
    }
}
