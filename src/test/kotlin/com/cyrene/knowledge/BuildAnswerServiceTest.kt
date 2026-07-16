package com.cyrene.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the pure core of the deterministic build path: which questions route to it
 * ([BuildAnswerService.wantedLabels]) and the fixed template it renders
 * ([BuildAnswerService.renderBuild]) — the whole point is that the same question can
 * never come back with a different layout or an effect on the wrong item.
 */
class BuildAnswerServiceTest {

    private val doc = """
        Phainon — build recomendada (Honkai: Star Rail).
        Relíquias (4 peças, melhor primeiro): Wavestrider Captain; Champion of Streetwise Boxing
        Ornamento Planar (melhor primeiro): Rutilant Arena
        Cone de Luz (melhor primeiro): Thus Burns the Dawn
        Main stats: Corpo: Chance Crít., Pés: ATQ%
        Substats (prioridade): Chance Crít. > Dano Crít.
        Equipe recomendada: Phainon, Cerydra, Cyrene
    """.trimIndent()

    // -------------------- wantedLabels (routing) -------------------- //

    @Test
    fun `build questions ask for every labeled line`() {
        assertEquals(
            GameKnowledgeTools.BUILD_LINE_LABELS.toSet(),
            BuildAnswerService.wantedLabels("qual a build do phainon?"),
        )
    }

    @Test
    fun `facet questions ask only for their lines`() {
        assertEquals(
            setOf("Equipe recomendada"),
            BuildAnswerService.wantedLabels("qual o melhor time do blade?"),
        )
        assertEquals(
            setOf("Relíquias", "Ornamento Planar"),
            BuildAnswerService.wantedLabels("qual a melhor relíquia e ornamento da hyacine?"),
        )
        assertEquals(
            setOf("Cone de Luz"),
            BuildAnswerService.wantedLabels("qual o efeito do cone da acheron?"),
        )
    }

    @Test
    fun `non-build questions do not route`() {
        assertTrue(BuildAnswerService.wantedLabels("quem é a bronya?").isEmpty())
        assertTrue(BuildAnswerService.wantedLabels("o que faz a e2 do phainon?").isEmpty())
        assertTrue(BuildAnswerService.wantedLabels("kit completo da acheron").isEmpty())
    }

    // -------------------- renderBuild (template) -------------------- //

    @Test
    fun `full build renders every block in doc order with effects under their own item`() {
        val effects = mapOf(
            "Wavestrider Captain" to "Bônus 2 peças: Chance Crít. +8%.\nBônus 4 peças: ATQ +48% ao usar a ultimate.",
            "Thus Burns the Dawn" to "Efeito (Morning Star): Dano da ultimate +60%.",
        )
        val out = BuildAnswerService.renderBuild(doc, GameKnowledgeTools.BUILD_LINE_LABELS.toSet(), effects)!!
        val expected = """
            **Phainon — build recomendada**

            **Relíquias (4 peças, melhor primeiro)**
            1. **Wavestrider Captain**
            · Bônus 2 peças: Chance Crít. +8%.
            · Bônus 4 peças: ATQ +48% ao usar a ultimate.
            2. **Champion of Streetwise Boxing**

            **Ornamento Planar (melhor primeiro)**
            1. **Rutilant Arena**

            **Cone de Luz (melhor primeiro)**
            1. **Thus Burns the Dawn**
            · Efeito (Morning Star): Dano da ultimate +60%.

            **Main stats**
            Corpo: Chance Crít., Pés: ATQ%

            **Substats (prioridade)**
            Chance Crít. > Dano Crít.

            **Equipe recomendada**
            Phainon, Cerydra, Cyrene
        """.trimIndent()
        assertEquals(expected, out)
    }

    @Test
    fun `facet render keeps only the asked blocks`() {
        val out = BuildAnswerService.renderBuild(doc, setOf("Equipe recomendada"), emptyMap())!!
        assertEquals(
            "**Phainon — build recomendada**\n\n**Equipe recomendada**\nPhainon, Cerydra, Cyrene",
            out,
        )
    }

    @Test
    fun `asked facet the doc lacks renders null so the caller falls through`() {
        val noTeam = doc.lines().filterNot { it.startsWith("Equipe") }.joinToString("\n")
        assertNull(BuildAnswerService.renderBuild(noTeam, setOf("Equipe recomendada"), emptyMap()))
    }

    @Test
    fun `effect lines are extracted from item docs, headers and stat lines are not`() {
        val relicDoc = """
            Rutilant Arena — Ornamento Planar (Planar Ornament) de Honkai: Star Rail.
            Bônus 2 peças: Chance Crít. +8%.
        """.trimIndent()
        assertEquals(listOf("Bônus 2 peças: Chance Crít. +8%."), BuildAnswerService.effectLines(relicDoc))
        val coneDoc = """
            Thus Burns the Dawn — Cone de Luz (Light Cone) de Honkai: Star Rail.
            Raridade: 5 estrelas
            Efeito (Morning Star): Dano da ultimate +60%.
        """.trimIndent()
        assertEquals(
            listOf("Efeito (Morning Star): Dano da ultimate +60%."),
            BuildAnswerService.effectLines(coneDoc),
        )
    }
}
