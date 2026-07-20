package com.cyrene.knowledge

import com.cyrene.hsr.BuildView
import com.cyrene.hsr.ItemEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the pure core of the deterministic build path: which questions route to it
 * ([BuildAnswerService.wantedLabels]) and the fixed template it renders from a [BuildView]
 * ([BuildAnswerService.renderBuild]) — the whole point is that the same question can never
 * come back with a different layout or an effect on the wrong item.
 */
class BuildAnswerServiceTest {

    private val view = BuildView(
        characterId = "1408",
        nome = "Phainon",
        nomeEn = "Phainon",
        reliquias = listOf(
            ItemEffect(
                "Wavestrider Captain", "Conjunto de Relíquia (Cavern Relics)",
                listOf("Bônus 2 peças: Chance Crít. +8%.", "Bônus 4 peças: ATQ +48% ao usar a ultimate."),
            ),
            ItemEffect("Champion of Streetwise Boxing", "Conjunto de Relíquia (Cavern Relics)", emptyList()),
        ),
        ornamentos = listOf(ItemEffect("Rutilant Arena", "Ornamento Planar (Planar Ornament)", emptyList())),
        cones = listOf(
            ItemEffect("Thus Burns the Dawn", "Cone de Luz (Light Cone)", listOf("Efeito (Morning Star): Dano da ultimate +60%.")),
        ),
        mainStatCorpo = "Chance Crít.",
        mainStatPes = "ATQ%",
        substatusRecomendados = "Chance Crít. > Dano Crít.",
        equipeRecomendada = "Phainon, Cerydra, Cyrene",
    )

    // -------------------- wantedLabels (routing) -------------------- //

    @Test
    fun `build questions ask for every labeled facet`() {
        assertEquals(
            GameKnowledgeTools.BUILD_LINE_LABELS.toSet(),
            BuildAnswerService.wantedLabels("qual a build do phainon?"),
        )
    }

    @Test
    fun `facet questions ask only for their lines`() {
        assertEquals(setOf("Equipe recomendada"), BuildAnswerService.wantedLabels("qual o melhor time do blade?"))
        assertEquals(setOf("Relíquias", "Ornamento Planar"), BuildAnswerService.wantedLabels("qual a melhor relíquia e ornamento da hyacine?"))
        assertEquals(setOf("Cone de Luz"), BuildAnswerService.wantedLabels("qual o efeito do cone da acheron?"))
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
        val out = BuildAnswerService.renderBuild(view, GameKnowledgeTools.BUILD_LINE_LABELS.toSet())!!
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

            **Substats**
            Chance Crít. > Dano Crít.

            **Equipe recomendada**
            Phainon, Cerydra, Cyrene
        """.trimIndent()
        assertEquals(expected, out)
    }

    @Test
    fun `facet render keeps only the asked blocks`() {
        assertEquals(
            "**Phainon — build recomendada**\n\n**Equipe recomendada**\nPhainon, Cerydra, Cyrene",
            BuildAnswerService.renderBuild(view, setOf("Equipe recomendada")),
        )
    }

    @Test
    fun `asked facet the build lacks renders null so the caller falls through`() {
        assertNull(BuildAnswerService.renderBuild(view.copy(equipeRecomendada = null), setOf("Equipe recomendada")))
    }
}
