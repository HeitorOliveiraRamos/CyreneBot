package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the placeholder/param substitution that turns nanoka's `#N[fmt]%` ability text into
 * the real numbers the model reads. This is the only non-trivial logic in the nanoka path,
 * so the formatting contracts (percent ×100, integer vs decimal, tag stripping, max-level
 * param selection) live here.
 */
class NanokaIngestionSourceTest {

    private val mapper = ObjectMapper()

    @Test
    fun `fill substitutes a percent placeholder and multiplies by 100`() {
        // "Deals Ice DMG equal to #1[i]% of ATK", param 0.85 -> 85%
        assertEquals(
            "Deals Ice DMG equal to 85% of ATK",
            NanokaIngestionSource.fill("Deals Ice DMG equal to #1[i]% of ATK", listOf(0.85)),
        )
    }

    @Test
    fun `fill substitutes a plain integer placeholder without scaling`() {
        assertEquals(
            "regenerates 6 Energy",
            NanokaIngestionSource.fill("regenerates #1[i] Energy", listOf(6.0)),
        )
    }

    @Test
    fun `fill handles multiple params and decimal format`() {
        assertEquals(
            "DEF 16% and RES 1.5",
            NanokaIngestionSource.fill("DEF #1[i]% and RES #2[f1]", listOf(0.16, 1.5)),
        )
    }

    @Test
    fun `fill strips color and unbreak tags`() {
        assertEquals(
            "buff 24%",
            NanokaIngestionSource.fill("buff <color=#fff><unbreak>#1[i]%</unbreak></color>", listOf(0.24)),
        )
    }

    @Test
    fun `fill leaves an unmatched placeholder visible rather than dropping it`() {
        assertEquals("a #2[i]", NanokaIngestionSource.fill("a #2[i]", listOf(1.0)))
    }

    @Test
    fun `maxLevelParams reads the param_list of the highest level`() {
        val level = mapper.readTree(
            """{"1":{"param_list":[0.5]},"2":{"param_list":[0.6]},"10":{"param_list":[0.85]}}""",
        )
        assertEquals(listOf(0.85), NanokaIngestionSource.maxLevelParams(level))
    }

    @Test
    fun `strip removes braces tokens and collapses whitespace`() {
        assertEquals("hello world", NanokaIngestionSource.strip("hello   {NICKNAME}world"))
    }

    // -------------------- buildDoc -------------------- //

    private val source = NanokaIngestionSource(BotProperties(token = "t", modelName = "m"), mapper)

    @Test
    fun `buildDoc resolves id lists to names, labels stats in PT and links the character id`() {
        val detail = mapper.readTree(
            """
            {"relics": {
               "set4_id_list": [117, 999],
               "set2_id_list": [314],
               "property_list": [
                 {"relic_type": "BODY", "property_type": "CriticalChanceBase"},
                 {"relic_type": "NECK", "property_type": "ThunderAddedRatio"},
                 {"relic_type": "HEAD", "property_type": "HPDelta"}
               ],
               "sub_affix_property_list": ["CriticalChanceBase", "AttackAddedRatio"]
             },
             "lightcones": [23024],
             "teams": [{"member_list": [1218]}]}
            """,
        )
        val doc = source.buildDoc(
            "Acheron", "1308", detail,
            relicNames = mapOf("117" to "Pioneer Diver of Dead Waters", "314" to "Izumo Gensei"),
            coneNames = mapOf("23024" to "Along the Passing Shore"),
            charNames = mapOf("1218" to "Jiaoqiu"),
        )!!
        val text = doc.text.orEmpty()
        assertTrue("Pioneer Diver of Dead Waters" in text)
        assertTrue("Cone de Luz (melhor primeiro): Along the Passing Shore" in text)
        assertTrue("Esfera Planar: Dano de Raio" in text)
        assertTrue("Substats (prioridade): Chance Crít. > ATQ%" in text)
        assertTrue("Time recomendado: Acheron, Jiaoqiu" in text)
        // Unresolvable id dropped, not rendered raw; fixed-main HEAD slot skipped.
        assertFalse("999" in text)
        assertFalse("HEAD" in text)
        assertEquals("build", doc.metadata["category"])
        assertEquals("1308", doc.metadata["character_id"])
    }

    @Test
    fun `buildDoc is null when there is nothing to recommend`() {
        val detail = mapper.readTree("""{"relics": {"set4_id_list": [999]}, "lightcones": []}""")
        assertNull(source.buildDoc("X", "1", detail, emptyMap(), emptyMap(), emptyMap()))
    }
}
