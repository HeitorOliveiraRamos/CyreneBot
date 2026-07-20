package com.cyrene.knowledge

import com.cyrene.hsr.ConeDeLuz
import com.cyrene.hsr.ItemEffect
import com.cyrene.knowledge.RosterAnswerService.Entity
import com.cyrene.knowledge.RosterAnswerService.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the [QueryPlan] contract: the deterministic parser (group/ordinal/pick shapes and the
 * fall-through negatives), the LLM-plan validator's strictness, and the pure renders.
 */
class PlanAnswerServiceTest {

    private val factions = listOf("Expresso Astral", "Estação Espacial Herta")

    // -------------------- deterministic parser -------------------- //

    @Test
    fun `parses a per-element group listing`() {
        val plan = PlanAnswerService.parse("me gera uma lista com 5 personagens de cada elemento")!!
        assertEquals(Entity.PERSONAGEM, plan.entity)
        assertEquals(Facet.ELEMENTO, plan.groupBy)
        assertEquals(5, plan.limit)
        assertEquals(Project.LIST, plan.project)
        assertNull(plan.elemento)
    }

    @Test
    fun `parses an ordinal cone ask for a named character`() {
        val plan = PlanAnswerService.parse(
            "me da o efeito do terceiro melhor cone pro Phainon",
            factions,
            namedIds = listOf("9001"),
        )!!
        assertEquals(Entity.CONE, plan.entity)
        assertEquals(3, plan.ordinal)
        assertEquals(listOf("9001"), plan.characterIds)
    }

    @Test
    fun `parses an indefinite pick with a build projection`() {
        val plan = PlanAnswerService.parse("me da a build de um personagem de gelo")!!
        assertEquals(Entity.PERSONAGEM, plan.entity)
        assertTrue(plan.pick)
        assertEquals("Gelo", plan.elemento)
        assertEquals(Project.BUILD, plan.project)
        assertTrue(plan.labels.containsAll(GameKnowledgeTools.BUILD_LINE_LABELS))
    }

    @Test
    fun `parses a single-facet pick projection`() {
        val plan = PlanAnswerService.parse("qual o cone de um personagem da destruição?")!!
        assertTrue(plan.pick)
        assertEquals("A Destruição", plan.caminho)
        assertEquals(setOf("Cone de Luz"), plan.labels)
    }

    @Test
    fun `groups cones by path`() {
        val plan = PlanAnswerService.parse("me lista 5 cones de cada caminho")!!
        assertEquals(Entity.CONE, plan.entity)
        assertEquals(Facet.CAMINHO, plan.groupBy)
        assertEquals(5, plan.limit)
    }

    @Test
    fun `an indefinite article is the per-group limit`() {
        val plan = PlanAnswerService.parse("um personagem de cada elemento")!!
        assertEquals(1, plan.limit)
    }

    @Test
    fun `a count over groups projects counts`() {
        val plan = PlanAnswerService.parse("quantos personagens tem de cada caminho?")!!
        assertEquals(Project.COUNT, plan.project)
        assertEquals(Facet.CAMINHO, plan.groupBy)
    }

    @Test
    fun `a rarity filter survives next to the grouped facet`() {
        val plan = PlanAnswerService.parse("personagens 5 estrelas de cada elemento")!!
        assertEquals(5, plan.raridade)
        assertEquals(Facet.ELEMENTO, plan.groupBy)
    }

    @Test
    fun `pick of a random cone renders its effect`() {
        val plan = PlanAnswerService.parse("me da o efeito de um cone da destruição")!!
        assertEquals(Entity.CONE, plan.entity)
        assertTrue(plan.pick)
        assertEquals("A Destruição", plan.caminho)
    }

    @Test
    fun `flat questions the other services own fall through`() {
        assertNull(PlanAnswerService.parse("me da 5 personagens de gelo", factions))
        assertNull(PlanAnswerService.parse("qual a build do phainon", factions, listOf("9001")))
        assertNull(PlanAnswerService.parse("melhor cone pra acheron", factions, listOf("9002")))
        assertNull(PlanAnswerService.parse("quais personagens combinam com a march", factions, listOf("9003")))
        assertNull(PlanAnswerService.parse("quantas relíquias tem no total?", factions))
    }

    @Test
    fun `ordinal words in everyday positions do not fire`() {
        // "segundo o guia" = "according to the guide", not slot 2.
        assertNull(PlanAnswerService.parse("segundo o guia, qual o kit da kafka?", factions, listOf("9004")))
        // Ordinal without a named character has no ranked list to index.
        assertNull(PlanAnswerService.parse("qual o segundo melhor cone?", factions))
    }

    @Test
    fun `a bare indefinite cone ask stays with the roster list`() {
        assertNull(PlanAnswerService.parse("me da um cone da destruição", factions))
    }

    // -------------------- looksPlannable gate -------------------- //

    @Test
    fun `plannable gate passes table talk and rejects entity questions`() {
        assertTrue(PlanAnswerService.looksPlannable("lista os personagens mais raros da caça"))
        assertTrue(PlanAnswerService.looksPlannable("quantos cones existem?"))
        assertTrue(PlanAnswerService.looksPlannable("um de cada elemento"))
        kotlin.test.assertFalse(PlanAnswerService.looksPlannable("quem é a acheron?"))
        kotlin.test.assertFalse(PlanAnswerService.looksPlannable("qual o kit da robin?"))
    }

    // -------------------- LLM plan validation -------------------- //

    private val resolve: (String) -> String? = { name ->
        mapOf("phainon" to "9001", "kafka" to "9005")[com.cyrene.hsr.HsrCharacterService.normalize(name)]
    }

    @Test
    fun `validates a group plan from json`() {
        val plan = PlanAnswerService.parseLlmPlan(
            """{"entidade":"personagem","agrupar_por":"elemento","quantidade":5}""",
            factions, resolve,
        )!!
        assertEquals(Facet.ELEMENTO, plan.groupBy)
        assertEquals(5, plan.limit)
    }

    @Test
    fun `validates an ordinal plan and resolves the character`() {
        val plan = PlanAnswerService.parseLlmPlan(
            """{"entidade":"cone","personagem":"Phainon","posicao":3,"mostrar":"efeito"}""",
            factions, resolve,
        )!!
        assertEquals(listOf("9001"), plan.characterIds)
        assertEquals(3, plan.ordinal)
    }

    @Test
    fun `validates a pick plan with canonical element`() {
        val plan = PlanAnswerService.parseLlmPlan(
            """{"entidade":"personagem","elemento":"gelo","sortear":true,"mostrar":"build"}""",
            factions, resolve,
        )!!
        assertTrue(plan.pick)
        assertEquals("Gelo", plan.elemento)
        assertTrue(plan.labels.isNotEmpty())
    }

    @Test
    fun `rejects plans with unresolvable values instead of narrowing them`() {
        // Unknown element.
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":"personagem","elemento":"sombra"}""", factions, resolve))
        // Character the gazetteer doesn't know.
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":"cone","personagem":"Lilita","posicao":1}""", factions, resolve))
        // Unknown faction.
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":"personagem","faccao":"Ordem Secreta"}""", factions, resolve))
        // Ordinal on a character entity / without a character.
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":"personagem","personagem":"Kafka","posicao":2}""", factions, resolve))
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":"cone","posicao":2}""", factions, resolve))
        // Featureless plan, null entity, broken JSON.
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":"personagem"}""", factions, resolve))
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":null}""", factions, resolve))
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":"personag""", factions, resolve))
        // A filter the entity can't honour.
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":"cone","elemento":"gelo"}""", factions, resolve))
        assertNull(PlanAnswerService.parseLlmPlan("""{"entidade":"reliquia","raridade":5}""", factions, resolve))
    }

    @Test
    fun `a faction count plan resolves the faction name`() {
        val plan = PlanAnswerService.parseLlmPlan(
            """{"entidade":"personagem","faccao":"expresso astral","mostrar":"contagem"}""",
            factions, resolve,
        )!!
        assertEquals("Expresso Astral", plan.faccao)
        assertEquals(Project.COUNT, plan.project)
    }

    // -------------------- renders -------------------- //

    @Test
    fun `ordinal render names the slot and carries the effect`() {
        val out = PlanAnswerService.renderOrdinal(
            "Phainon", 3, Entity.CONE,
            ItemEffect("Espada do Sol", "Cone de Luz (Light Cone)", listOf("Efeito (Brilho): aumenta o dano em 24%.")),
        )
        assertEquals(
            "**Phainon — 3º Cone de Luz recomendado**\n**Espada do Sol**\n· Efeito (Brilho): aumenta o dano em 24%.",
            out,
        )
    }

    @Test
    fun `group render titles the listing and caps per group`() {
        val plan = QueryPlan(Entity.PERSONAGEM, groupBy = Facet.ELEMENTO, limit = 1)
        val out = PlanAnswerService.renderGroups(
            plan,
            mapOf(
                "Gelo" to listOf(Row("Jingliu", "A Destruição · 5★"), Row("Herta", "A Erudição · 4★")),
                "Fogo" to listOf(Row("Himeko", "A Erudição · 5★")),
            ),
        )!!
        assertTrue(out.startsWith("**Personagens por Elemento**"), out)
        assertTrue(out.contains("**Fogo** (1)"), out)
        assertTrue(out.contains("**Gelo** (1 de 2)"), out)
        // Capped at 1 per group: exactly two bullet lines in total.
        assertEquals(2, out.lines().count { it.startsWith("- ") }, out)
    }

    @Test
    fun `count projection renders one line per group`() {
        val plan = QueryPlan(Entity.PERSONAGEM, groupBy = Facet.CAMINHO, project = Project.COUNT)
        val out = PlanAnswerService.renderGroups(
            plan,
            mapOf("A Caça" to listOf(Row("Seele", null)), "A Harmonia" to List(3) { Row("R$it", null) }),
        )!!
        assertTrue(out.contains("**A Caça**: 1"), out)
        assertTrue(out.contains("**A Harmonia**: 3"), out)
    }

    @Test
    fun `pick line names the filters and the draw`() {
        val plan = QueryPlan(Entity.PERSONAGEM, elemento = "Gelo", pick = true)
        assertEquals("Sorteado (Gelo): **Jingliu**", PlanAnswerService.pickLine("Jingliu", plan))
    }

    @Test
    fun `cone render shows facts and effect`() {
        val cone = ConeDeLuz(
            nome = "Noite Sem Fim", caminho = "A Destruição", raridade = 5,
            efeitoNome = "Vigília", efeitoDescricao = "Aumenta o ATQ em 16%.", descricao = null,
        )
        assertEquals(
            "**Noite Sem Fim** (5★ · A Destruição)\nEfeito (Vigília): Aumenta o ATQ em 16%.",
            PlanAnswerService.renderCone(cone),
        )
    }
}
