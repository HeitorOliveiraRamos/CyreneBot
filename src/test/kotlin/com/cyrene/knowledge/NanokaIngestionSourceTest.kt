package com.cyrene.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
