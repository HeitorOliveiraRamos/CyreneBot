package com.cyrene.hsr

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the pure TS-parsing contract of the fribbels harvest against fixtures shaped
 * exactly like the machine-formatted repo files (a DPS with `simulation()` and a support
 * without one), plus the enum/property mappings and the JSONB round-trip.
 */
class FribbelsHarvesterTest {

    // Condensed from src/lib/conditionals/character/1400/Castorice.ts — real structure.
    private val dpsTs = """
        const conditionals = { id: 'not-a-char-id', teammates: [{ characterId: Cyrene.id }] }

        const simulation = (): SimulationMetadata => ({
          parts: {
            [Parts.Body]: [
              Stats.CR,
              Stats.CD,
            ],
          },
          substats: [
            Stats.CD,
            Stats.CR,
            Stats.HP_P,
          ],
          relicSets: [
            [Sets.PoetOfMourningCollapse, Sets.PoetOfMourningCollapse],
            ...SPREAD_RELICS_4P_GENERAL_CONDITIONALS,
          ],
          ornamentSets: [
            Sets.BoneCollectionsSereneDemesne,
            ...SPREAD_ORNAMENTS_2P_GENERAL_CONDITIONALS,
          ],
          teammates: [
            {
              characterId: Cyrene.id,
              lightCone: ThisLoveForever.id,
            },
          ],
        })

        const scoring = (): ScoringMetadata => ({
          stats: {
            [Stats.ATK]: 0,
            [Stats.HP]: 1,
            [Stats.HP_P]: 1,
            [Stats.CR]: 1,
            [Stats.CD]: 1,
            [Stats.SPD]: 0,
          },
          parts: {
            [Parts.Body]: [
              Stats.CR,
              Stats.CD,
              Stats.HP_P,
            ],
            [Parts.Feet]: [
              Stats.HP_P,
            ],
            [Parts.PlanarSphere]: [
              Stats.HP_P,
              Stats.Quantum_DMG,
            ],
            [Parts.LinkRope]: [
              Stats.HP_P,
            ],
          },
          simulation: simulation(),
        })

        const display = { imageCenter: { x: 875 } }

        export const Castorice: CharacterConfig = {
          id: '1407',
          defaultLightCone: MakeFarewellsMoreBeautiful.id,
        }
    """.trimIndent()

    // Support without a simulation block (Asta-like).
    private val supportTs = """
        const scoring = (): ScoringMetadata => ({
          stats: {
            [Stats.SPD]: 1,
            [Stats.DEF]: 0.25,
          },
          parts: {
            [Parts.Body]: [],
            [Parts.Feet]: [
              Stats.SPD,
            ],
            [Parts.LinkRope]: [
              Stats.ATK_P,
              Stats.ERR,
            ],
          },
        })

        const display = { disableSpine: true }

        export const Asta: CharacterConfig = {
          id: '1009',
        }
    """.trimIndent()

    @Test
    fun `parseCharacter extracts scoring and simulation from a DPS config`() {
        val parsed = FribbelsHarvester.parseCharacter(dpsTs)!!
        assertEquals("1407", parsed.id)
        assertEquals(mapOf("ATK" to 0.0, "HP" to 1.0, "HP_P" to 1.0, "CR" to 1.0, "CD" to 1.0, "SPD" to 0.0), parsed.stats)
        // scoring parts win over the simulation's own parts block
        assertEquals(listOf("CR", "CD", "HP_P"), parsed.parts["Body"])
        assertEquals(listOf("HP_P", "Quantum_DMG"), parsed.parts["PlanarSphere"])
        assertEquals(listOf("PoetOfMourningCollapse" to "PoetOfMourningCollapse"), parsed.relicSets)
        assertEquals(listOf("BoneCollectionsSereneDemesne"), parsed.ornamentSets)
        assertEquals(listOf("CD", "CR", "HP_P"), parsed.substats)
    }

    @Test
    fun `parseCharacter handles a support without simulation`() {
        val parsed = FribbelsHarvester.parseCharacter(supportTs)!!
        assertEquals("1009", parsed.id)
        assertEquals(mapOf("SPD" to 1.0, "DEF" to 0.25), parsed.stats)
        assertEquals(emptyList(), parsed.parts["Body"])
        assertEquals(listOf("ATK_P", "ERR"), parsed.parts["LinkRope"])
        assertTrue(parsed.relicSets.isEmpty())
        assertTrue(parsed.ornamentSets.isEmpty())
        assertTrue(parsed.substats.isEmpty())
    }

    @Test
    fun `parseCharacter returns null without a config id`() {
        assertNull(FribbelsHarvester.parseCharacter("const scoring = () => ({ stats: {} })"))
    }

    @Test
    fun `parseSetsEnum reads names including escaped quotes`() {
        val ts = """
            export const OtherEnum = { Ignored: 'nope' } as const
            export const Sets = {
              PasserbyOfWanderingCloud: 'Passerby of Wandering Cloud',
              BoneCollectionsSereneDemesne: 'Bone Collection\'s Serene Demesne',
            } as const
        """.trimIndent()
        val sets = FribbelsHarvester.parseSetsEnum(ts)
        assertEquals("Passerby of Wandering Cloud", sets["PasserbyOfWanderingCloud"])
        assertEquals("Bone Collection's Serene Demesne", sets["BoneCollectionsSereneDemesne"])
        assertNull(sets["Ignored"])
    }

    @Test
    fun `toMeta maps enum keys to game properties and PT set names`() {
        val parsed = FribbelsHarvester.parseCharacter(dpsTs)!!
        val meta = parsed.toMeta(
            setsEnum = mapOf(
                "PoetOfMourningCollapse" to "Poet of Mourning Collapse",
                "BoneCollectionsSereneDemesne" to "Bone Collection's Serene Demesne",
            ),
            setPtByEn = mapOf("Poet of Mourning Collapse" to "Poeta do Colapso do Luto"),
        )
        assertEquals(1.0, meta.subWeights["CriticalChanceBase"])
        assertEquals(0.0, meta.subWeights["AttackDelta"])
        assertEquals(listOf("CriticalChanceBase", "CriticalDamageBase", "HPAddedRatio"), meta.mainStats[3])
        assertEquals(listOf("HPAddedRatio", "QuantumAddedRatio"), meta.mainStats[5])
        // pt name when StarRailRes maps it, en fallback otherwise
        assertEquals(listOf(listOf("Poeta do Colapso do Luto", "Poeta do Colapso do Luto")), meta.relicSets)
        assertEquals(listOf("Bone Collection's Serene Demesne"), meta.ornamentSets)
        assertEquals(listOf("CriticalDamageBase", "CriticalChanceBase", "HPAddedRatio"), meta.substatPriority)
    }

    @Test
    fun `FribbelsMeta survives the JSONB round-trip`() {
        val mapper = ObjectMapper()
        val meta = FribbelsMeta(
            subWeights = mapOf("CriticalChanceBase" to 1.0, "SpeedDelta" to 0.5),
            mainStats = mapOf(3 to listOf("CriticalChanceBase"), 6 to listOf("HPAddedRatio")),
            relicSets = listOf(listOf("A", "A"), listOf("B", "C")),
            ornamentSets = listOf("X"),
            substatPriority = listOf("CriticalDamageBase"),
        )
        assertEquals(meta, FribbelsMeta.fromJson(mapper.readTree(meta.toJson(mapper))))
    }

    @Test
    fun `fribbelsWeights builds a usable CharWeights with theoretical-best max`() {
        val meta = FribbelsMeta(
            subWeights = mapOf(
                "CriticalDamageBase" to 1.0, "CriticalChanceBase" to 1.0,
                "HPAddedRatio" to 1.0, "HPDelta" to 0.5, "AttackDelta" to 0.0,
            ),
            mainStats = mapOf(3 to listOf("CriticalChanceBase")),
            relicSets = emptyList(),
            ornamentSets = emptyList(),
            substatPriority = emptyList(),
        )
        val w = BuildAnalyzer.fribbelsWeights(meta)!!
        assertEquals(mapOf("CriticalChanceBase" to 1.0), w.main[3])
        assertEquals(mapOf("HPDelta" to 1.0), w.main[1])
        // 1.2 × (6×1.0 + 1.0 + 1.0 + 0.5) = 10.2
        assertEquals(10.2, w.max, 1e-9)
        assertNull(BuildAnalyzer.fribbelsWeights(meta.copy(subWeights = mapOf("HPDelta" to 0.0))))
    }
}
