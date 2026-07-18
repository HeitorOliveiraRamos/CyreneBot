package com.cyrene.hsr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the pure extraction ([HsrCharacterHarvester.buildRow]) against real srs + nanoka
 * fixtures for The Herta (1401): PT-first when srs is present, English fallback when it isn't.
 */
class HsrCharacterHarvesterTest {

    private val mapper = ObjectMapper()
    private fun load(name: String): JsonNode =
        mapper.readTree(javaClass.getResourceAsStream("/hsr/$name") ?: error("missing fixture $name"))

    @Test
    fun `buildRow pulls the full PT kit from srs when the character is released`() {
        val row = HsrCharacterHarvester.buildRow(
            id = "1401",
            nanMeta = load("nanoka_index_1401.json"),
            srsEntry = load("srs_entry_theherta.json"),
            srsDetail = load("srs_detail_theherta.json"),
            nanDetail = load("nanoka_detail_1401.json"),
        )
        assertEquals("A Herta", row.namePt)
        assertEquals("The Herta", row.nameEn)
        assertEquals("Gelo", row.elemento)
        assertEquals(5, row.raridade)
        assertEquals("Estação Espacial Herta", row.faccao)
        assertTrue(row.caminho!!.isNotBlank())
        assertTrue(row.descricao!!.contains("Sociedade de Gênios"), "desc: ${row.descricao}")
        // Abilities land in the right column, headed by their PT name.
        assertTrue(row.atqBasico!!.startsWith("Você Compreendeu?"), "basic: ${row.atqBasico}")
        assertTrue(row.periciaSuprema!!.contains("Mágica Acontece"), "ult: ${row.periciaSuprema}")
        assertTrue(row.talento!!.isNotBlank())
        // Six eidolons, three major traces, all four story parts + details.
        assertTrue(row.eidolon1!!.isNotBlank()); assertTrue(row.eidolon6!!.isNotBlank())
        assertTrue(row.tracoA2!!.isNotBlank()); assertTrue(row.tracoA6!!.isNotBlank())
        assertTrue(row.detalhesPersonagem!!.contains("madame Herta"), "details: ${row.detalhesPersonagem}")
        assertTrue(row.historiaParte1!!.isNotBlank()); assertTrue(row.historiaParte4!!.isNotBlank())
    }

    @Test
    fun `buildRow falls back to nanoka English when srs has no such character`() {
        val row = HsrCharacterHarvester.buildRow(
            id = "1401",
            nanMeta = load("nanoka_index_1401.json"),
            srsEntry = null,
            srsDetail = null,
            nanDetail = load("nanoka_detail_1401.json"),
        )
        assertNull(row.namePt)
        assertEquals("The Herta", row.nameEn)
        assertEquals("Ice", row.elemento)
        assertEquals("Erudition", row.caminho) // internal "Mage" → readable EN
        assertEquals(5, row.raridade)
        assertTrue(row.descricao!!.contains("Genius Society"), "desc: ${row.descricao}")
        assertTrue(row.atqBasico!!.contains("Did You Get It"), "basic: ${row.atqBasico}")
        assertTrue(row.eidolon1!!.isNotBlank())
    }

    @Test
    fun `buildRow resolves the Trailblazer name placeholder and maps the internal path codename`() {
        val meta = mapper.readTree(
            """{"en":"{NICKNAME}","baseType":"Warrior","damageType":"Physical","rank":"CombatPowerAvatarRarityType5"}""",
        )
        val row = HsrCharacterHarvester.buildRow("8001", meta, srsEntry = null, srsDetail = null, nanDetail = null)
        assertEquals("Trailblazer", row.nameEn)
        assertEquals("Destruction", row.caminho)
        assertEquals("Physical", row.elemento)
        assertEquals(5, row.raridade)
        assertNull(row.namePt)
    }
}
