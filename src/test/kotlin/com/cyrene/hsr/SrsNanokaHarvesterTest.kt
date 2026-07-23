package com.cyrene.hsr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the pure extraction of [SrsNanokaHarvester] against real srs + nanoka fixtures:
 * split abilities (base kit), memosprite (Recordação), euphoria (Euforia), relic/ornament pieces,
 * light-cone lore, and the signature-cone wiring.
 */
class SrsNanokaHarvesterTest {

    private val mapper = ObjectMapper()
    private fun load(name: String): JsonNode =
        mapper.readTree(javaClass.getResourceAsStream("/hsr/$name") ?: error("missing fixture $name"))

    // -------------------- characters -------------------- //

    @Test
    fun `base kit comes PT-first, split into nome and descricao`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1401",
            nanMeta = load("nanoka_index_1401.json"),
            srsEntry = load("srs_entry_theherta.json"),
            srsDetail = load("srs_detail_theherta.json"),
            nanDetail = load("nanoka_detail_1401.json"),
        )
        assertEquals("1401", p.characterId)
        assertEquals("A Herta", p.nome)
        assertEquals("The Herta", p.nomeEn)
        assertEquals("Gelo", p.elemento)
        assertEquals(5, p.raridade)
        assertEquals("Estação Espacial Herta", p.faccao)
        assertTrue(p.caminho!!.contains("Erudição"), "caminho: ${p.caminho}")
        // Abilities split: name in _nome, multiplier text in _descricao.
        assertEquals("Você Compreendeu?", p.atqBasico.nome)
        assertTrue(p.atqBasico.descricao!!.isNotBlank())
        assertTrue(p.periciaSuprema.nome!!.contains("Mágica Acontece"), "ult nome: ${p.periciaSuprema.nome}")
        assertEquals(6, p.eidolons.size)
        assertEquals(3, p.tracos.size)
        // Erudition unit → no memosprite, no euphoria.
        assertNull(p.periciaMemoespirito.nome)
        assertNull(p.talentoMemoespirito.nome)
        assertNull(p.periciaEuforia.nome)
    }

    @Test
    fun `Recordacao unit fills memosprite skill and talent, PT`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1402",
            nanMeta = load("nanoka_index_1402.json"),
            srsEntry = load("srs_entry_aglaea.json"),
            srsDetail = load("srs_detail_aglaea.json"),
            nanDetail = load("nanoka_detail_1402.json"),
        )
        assertEquals("Aglaea", p.nome)
        assertTrue(p.caminho!!.contains("Recordação"), "caminho: ${p.caminho}")
        assertEquals("Amphoreus", p.faccao)
        assertEquals("Armadilha de Espinhos", p.periciaMemoespirito.nome)
        assertTrue(p.periciaMemoespirito.descricao!!.isNotBlank())
        assertEquals("Um Corpo Feito de Lágrimas", p.talentoMemoespirito.nome)
        // Not an Elation unit.
        assertNull(p.periciaEuforia.nome)
    }

    @Test
    fun `Euforia unit fills the euphoria skill and has no memosprite`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1501",
            nanMeta = load("nanoka_index_1501.json"),
            srsEntry = load("srs_entry_sparxie.json"),
            srsDetail = load("srs_detail_sparxie.json"),
            nanDetail = load("nanoka_detail_1501.json"),
        )
        assertEquals("Sparxie", p.nome)
        assertTrue(p.caminho!!.contains("Euforia"), "caminho: ${p.caminho}")
        assertEquals("Explosão de Sinal: O Grande Bis!", p.periciaEuforia.nome)
        assertTrue(p.periciaEuforia.descricao!!.isNotBlank())
        assertNull(p.periciaMemoespirito.nome)
        assertNull(p.talentoMemoespirito.nome)
    }

    // -------------------- relics / ornaments -------------------- //

    @Test
    fun `cavern set carries 2 and 4pc bonuses and all four pieces`() {
        val r = SrsNanokaHarvester.buildReliquia(
            entry = load("srs_relics_entry_101.json"),
            detail = load("srs_relics_detail_101.json"),
        )
        assertEquals("Transeunte da Nuvem Errante", r.nome)
        assertTrue(r.efeito2Pecas!!.contains("10%"), "2pc: ${r.efeito2Pecas}") // #1[i]% of 0.1 → 10%
        assertTrue(r.efeito4Pecas!!.contains("Ponto de Perícia"), "4pc: ${r.efeito4Pecas}")
        assertEquals("Prendedor de Cabelo Rejuvenescido do Transeunte", r.cabeca.nome)
        assertTrue(r.cabeca.descricao!!.isNotBlank())
        assertTrue(r.pes.nome!!.contains("Botas"), "pés: ${r.pes.nome}")
    }

    @Test
    fun `planar set carries the 2pc bonus, sphere and rope`() {
        val o = SrsNanokaHarvester.buildOrnamento(
            entry = load("srs_relics_entry_301.json"),
            detail = load("srs_relics_detail_301.json"),
        )
        assertEquals("Estação de Vedação Espacial", o.nome)
        assertTrue(o.efeito2Pecas!!.isNotBlank())
        assertEquals("Estação Espacial de Herta", o.esfera.nome)
        assertEquals("Jornada Nômade de Herta", o.corda.nome)
    }

    // -------------------- light cones + signature -------------------- //

    @Test
    fun `srs cone carries effect and lore, and wires its signature owner`() {
        val cone = SrsNanokaHarvester.buildSrsCone("23036", load("srs_cone_23036.json"))
        assertEquals("Tempo Tecido em Ouro", cone.nome)
        assertEquals(5, cone.raridade)
        assertTrue(cone.caminho!!.contains("Recordação"), "caminho: ${cone.caminho}")
        assertEquals("Estabelecimento", cone.efeitoNome)
        assertTrue(cone.efeitoDescricao!!.isNotBlank())
        assertTrue(cone.descricao!!.isNotBlank(), "descricao (lore) should be set")

        // Signature join: Aglaea's #1 recommended cone is this 5★ cone, so the harvest links them.
        val topCone = load("nanoka_detail_1402.json").path("lightcones").first().asText()
        assertEquals(cone.coneGameId, topCone)
    }

    // -------------------- recommended builds -------------------- //

    @Test
    fun `build extracts recommended relics, ornaments, cones, main stats, substats and team`() {
        val names = mapOf("1402" to "Aglaea", "1415" to "Trailblazer", "1313" to "Sunday", "1409" to "Hyacine")
        val b = SrsNanokaHarvester.buildBuild("1402", load("nanoka_detail_1402.json"), names)!!
        assertEquals("1402", b.characterGameId)
        // Shared game ids kept raw, best-first, capped to 3.
        assertEquals(listOf("123", "102", "109"), b.reliquiaGameIds)
        assertEquals(listOf("318", "302", "301"), b.ornamentoGameIds)
        assertEquals(listOf("23036", "21051", "21052"), b.coneGameIds)
        // property_list slots -> PT stat labels.
        assertEquals("Chance Crít.", b.mainStatCorpo)     // BODY / CriticalChanceBase
        assertEquals("Velocidade", b.mainStatPes)          // FOOT / SpeedDelta
        assertEquals("Dano de Raio", b.mainStatEsfera)     // NECK / ThunderAddedRatio
        assertEquals("Regen. de Energia", b.mainStatCorda) // OBJECT / SPRatioBase
        assertTrue(b.substatusRecomendados!!.startsWith("Chance Crít. > Dano Crít."), "subs: ${b.substatusRecomendados}")
        // Team resolved to display names, the character itself first.
        assertEquals("Aglaea, Trailblazer, Sunday, Hyacine", b.equipeRecomendada)
    }

    @Test
    fun `build is null when there are no recommended relics or cones`() {
        assertNull(SrsNanokaHarvester.buildBuild("9999", mapper.createObjectNode(), emptyMap()))
    }

    // -------------------- enhanced states + level caps -------------------- //

    @Test
    fun `enhanced-state character extracts the enhanced kit, PT from srs`() {
        // Firefly (id 1310, srs pageId "sam") has hasEnhanced=true → the enhanced (SAM-form) skills.
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1310",
            nanMeta = load("nanoka_index_1310.json"),
            srsEntry = load("srs_entry_sam.json"),
            srsDetail = load("srs_detail_sam.json"),
            nanDetail = load("nanoka_detail_1310.json"),
        )
        assertEquals("Vaga-lume", p.nome)
        assertEquals("Firefly", p.nomeEn)
        // Enhanced Basic/Skill, not the base "Ordem: ..." forms.
        assertEquals("Vaga-lume Tipo-IV: Dizimação Pirogênica", p.atqBasico.nome)
        assertEquals("Vaga-lume Tipo-IV: Sobrecarga da Estrela da Morte", p.pericia.nome)
    }

    @Test
    fun `enhanced kit that only changes descriptions is sourced from the enhanced object (Kafka)`() {
        // Kafka's base skillGrouping is single-id (same ability names) — the enhanced descriptions
        // live ONLY under .enhanced, so the fix must source skills from there.
        val kafka = load("srs_detail_kafka.json")
        fun skillId(buckets: List<List<JsonNode>>, tag: String) =
            buckets.map { it.first() }.first { it.path("typeDescHash").asText() == tag }.path("id").asLong()
        assertEquals(25635354L, skillId(SrsNanokaHarvester.srsCanonicalBuckets(kafka, enhanced = true), "Perícia"))
        assertEquals(874958L, skillId(SrsNanokaHarvester.srsCanonicalBuckets(kafka, enhanced = false), "Perícia"))
        // End-to-end: the enhanced Skill description lands in pericia_descricao.
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1005",
            nanMeta = load("nanoka_index_1005.json"),
            srsEntry = load("srs_entry_kafka.json"),
            srsDetail = kafka,
            nanDetail = load("nanoka_detail_1005.json"),
        )
        assertEquals("Luar Carinhoso", p.pericia.nome)
        assertTrue(p.pericia.descricao!!.isNotBlank())
    }

    @Test
    fun `enhanced state also updates eidolons and traces (Kafka)`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1005",
            nanMeta = load("nanoka_index_1005.json"),
            srsEntry = load("srs_entry_kafka.json"),
            srsDetail = load("srs_detail_kafka.json"),
            nanDetail = load("nanoka_detail_1005.json"),
        )
        // Eidolon 1 "Da Capo": enhanced text ("Ao usar um ataque…"), not base ("Quando o Talento…").
        val e1 = p.eidolons[0].descricao!!
        assertTrue(e1.contains("Ao usar um ataque"), "E1 should be enhanced: $e1")
        assertTrue(!e1.contains("Quando o Talento ativa"), "E1 must not be the base text")
        // A2 trace (enhanceId=1 variant): enhanced text, not the base "Ao usar a Perícia Suprema…".
        val a2 = p.tracos[0].descricao!!
        assertTrue(a2.contains("Taxa de Acerto de Efeito do aliado"), "A2 should be enhanced: $a2")
        assertEquals(3, p.tracos.size)
    }

    @Test
    fun `a new enhanced kit in nanoka wins when srs has not published it yet`() {
        // srsDetail=null models srs lacking the (enhanced) kit — the one case nanoka overrides srs.
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1310",
            nanMeta = load("nanoka_index_1310.json"),
            srsEntry = null,
            srsDetail = null,
            nanDetail = load("nanoka_detail_1310.json"),
        )
        assertEquals("Fyrefly Type-IV: Pyrogenic Decimation", p.atqBasico.nome)
    }

    @Test
    fun `extra ids in a skillGrouping bucket are merged in as bracketed sub-ability blocks`() {
        // A base (non-enhanced) ability card that spans two skills — the shape behind the missing
        // 「Estilo Livre Tohsaka」 (Rin), 「…Permissão…」 (Gilgamesh) text. Both must land in descricao.
        val srsDetail = mapper.readTree(
            """
            {
              "skills": [
                {"id": 1, "typeDescHash": "Talento", "name": "Taumaturgia de Gemas",
                 "descHash": "Base talento ganha <nobr>#1[i]</nobr> ponto.",
                 "levelData": [{"level": 10, "params": [20]}]},
                {"id": 2, "typeDescHash": "Talento", "name": "Estilo Livre Tohsaka",
                 "descHash": "Ataque Extra causando <nobr>#1[i]%</nobr> do ATQ.",
                 "levelData": [{"level": 10, "params": [3.0]}]}
              ],
              "skillGrouping": [[1, 2]]
            }
            """.trimIndent(),
        )
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "9001", nanMeta = mapper.createObjectNode(), srsEntry = null, srsDetail = srsDetail, nanDetail = null,
        )
        assertEquals("Taumaturgia de Gemas", p.talento.nome)
        val d = p.talento.descricao!!
        assertTrue(d.startsWith("Base talento ganha 20 ponto."), "primary block first: $d")
        assertTrue(d.contains("「Estilo Livre Tohsaka」"), "extra block header missing: $d")
        assertTrue(d.contains("Ataque Extra causando 300% do ATQ."), "extra block body missing: $d")
    }

    @Test
    fun `ability params are filled at the capped level, not the eidolon-boosted max`() {
        // The Herta Basic ATK (id 14751013): lvl6 params start 1.0, the boosted lvl10 start 1.4.
        val basic = load("srs_detail_theherta.json").path("skills").first { it.path("id").asLong() == 14751013L }.path("levelData")
        val expected6 = basic.first { it.path("level").asInt() == 6 }.path("params").map { it.asDouble() }
        assertEquals(expected6, SrsNanokaHarvester.srsParamsCapped(basic, 6))
        assertTrue(SrsNanokaHarvester.srsParamsCapped(basic, 6) != SrsNanokaHarvester.srsParamsCapped(basic, null),
            "cap-6 must differ from the max-level params")

        // nanoka Firefly enhanced Basic (131008): lvl6 differs from the lvl10 max too.
        val nanBasicLevel = load("nanoka_detail_1310.json").path("skills").path("131008").path("level")
        val exp6 = nanBasicLevel.path("6").path("param_list").map { it.asDouble() }
        assertEquals(exp6, SrsNanokaHarvester.nanParamsCapped(nanBasicLevel, 6))
        assertTrue(SrsNanokaHarvester.nanParamsCapped(nanBasicLevel, 6) != SrsNanokaHarvester.nanParamsCapped(nanBasicLevel, null))
    }
}
