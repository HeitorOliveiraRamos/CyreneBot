package com.cyrene.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the two bits of starrailstation logic that silently break the whole ingest if wrong:
 * the path hash (a one-char drift 404s every fetch) and the max-level/skill-grouping selection
 * that turns the raw JSON into the right ability text. Placeholder substitution itself is
 * covered by [NanokaIngestionSourceTest] since [fill]/[strip] are shared.
 */
class StarRailStationIngestionSourceTest {

    private val mapper = ObjectMapper()

    @Test
    fun `hashPath matches the site's own path hashes`() {
        // Values captured from the live client (deployment-independent — it's just hash(path)).
        assertEquals("1asvra9", StarRailStationIngestionSource.hashPath("pt/characters.json"))
        assertEquals("1uxjgp", StarRailStationIngestionSource.hashPath("pt/searchItems.json"))
    }

    @Test
    fun `maxLevelParams reads the params of the highest level entry`() {
        val levelData = mapper.readTree(
            """[{"level":1,"params":[0.5]},{"level":7,"params":[0.9]},{"level":3,"params":[0.7]}]""",
        )
        assertEquals(listOf(0.9), StarRailStationIngestionSource.maxLevelParams(levelData))
    }

    @Test
    fun `canonicalSkills picks the first id of each grouping bucket, dropping variants`() {
        val detail = mapper.readTree(
            """
            {"skills":[
               {"id":1,"name":"Basic"},
               {"id":2,"name":"Skill"},
               {"id":3,"name":"Ultimate"},
               {"id":30,"name":"Ultimate (enhanced)"},
               {"id":4,"name":"Talent"}
             ],
             "skillGrouping":[[1],[2],[3,30],[4]]}
            """,
        )
        assertEquals(
            listOf("Basic", "Skill", "Ultimate", "Talent"),
            StarRailStationIngestionSource.canonicalSkills(detail).map { it.path("name").asText() },
        )
    }

    @Test
    fun `canonicalSkills falls back to name-deduped skills when grouping is absent`() {
        val detail = mapper.readTree(
            """{"skills":[{"id":1,"name":"A"},{"id":2,"name":"A"},{"id":3,"name":"B"}]}""",
        )
        assertEquals(
            listOf("A", "B"),
            StarRailStationIngestionSource.canonicalSkills(detail).map { it.path("name").asText() },
        )
    }
}
