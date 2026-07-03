package com.cyrene.hsr

import com.fasterxml.jackson.databind.JsonNode

/**
 * Minimal projection of mihomo's `sr_info_parsed` response — only the fields the build
 * analyzer needs. Parsed by hand from [JsonNode] (no jackson-kotlin module in the project),
 * mirroring the StarRailRes `CharacterInfo`/`RelicInfo` schema:
 *
 *  - [MihomoRelic.slot] is the 1–6 piece type (head/hands/body/feet/sphere/rope) and lines
 *    up with StarRailScore's per-slot `main` weight keys;
 *  - affix [MihomoAffix.type] carries the game property key ("AttackAddedRatio",
 *    "CriticalChanceBase", "SpeedDelta"...) — exactly the keys StarRailScore weights use;
 *  - sub-affix [MihomoSubAffix.count]/[MihomoSubAffix.step] are the base/boost roll counts
 *    the community score formula consumes.
 */
data class MihomoProfile(
    val nickname: String,
    val characters: List<MihomoCharacter>,
)

data class MihomoCharacter(
    val id: String,
    val name: String,
    val level: Int,
    val eidolon: Int,
    val pathName: String,
    val elementName: String,
    val lightCone: MihomoLightCone?,
    val relics: List<MihomoRelic>,
    val relicSets: List<MihomoRelicSet>,
)

data class MihomoLightCone(
    val name: String,
    val superimposition: Int,
    val level: Int,
)

data class MihomoRelic(
    val name: String,
    val slot: Int,
    val setName: String,
    val rarity: Int,
    val level: Int,
    val mainAffix: MihomoAffix?,
    val subAffixes: List<MihomoSubAffix>,
)

data class MihomoAffix(
    val type: String,
    val name: String,
    val display: String,
)

data class MihomoSubAffix(
    val type: String,
    val name: String,
    val display: String,
    val count: Int,
    val step: Int,
)

/** Active set bonus: name + how many pieces of it are equipped (2/4). */
data class MihomoRelicSet(
    val name: String,
    val pieces: Int,
)

internal object MihomoParser {

    /** Maps the parsed-API JSON to [MihomoProfile]. Pure, so it's testable from a fixture. */
    fun parse(root: JsonNode): MihomoProfile = MihomoProfile(
        nickname = root.path("player").path("nickname").asText(""),
        characters = root.path("characters").map { c ->
            MihomoCharacter(
                id = c.path("id").asText(""),
                name = c.path("name").asText(""),
                level = c.path("level").asInt(0),
                eidolon = c.path("rank").asInt(0),
                pathName = c.path("path").path("name").asText(""),
                elementName = c.path("element").path("name").asText(""),
                lightCone = c.path("light_cone").takeIf { it.isObject }?.let { lc ->
                    MihomoLightCone(
                        name = lc.path("name").asText(""),
                        superimposition = lc.path("rank").asInt(0),
                        level = lc.path("level").asInt(0),
                    )
                },
                relics = c.path("relics").map { r ->
                    MihomoRelic(
                        name = r.path("name").asText(""),
                        slot = r.path("type").asInt(0),
                        setName = r.path("set_name").asText(""),
                        rarity = r.path("rarity").asInt(0),
                        level = r.path("level").asInt(0),
                        mainAffix = r.path("main_affix").takeIf { it.isObject }?.let { affix(it) },
                        subAffixes = r.path("sub_affix").map { s ->
                            MihomoSubAffix(
                                type = s.path("type").asText(""),
                                name = s.path("name").asText(""),
                                display = s.path("display").asText(""),
                                count = s.path("count").asInt(0),
                                step = s.path("step").asInt(0),
                            )
                        },
                    )
                },
                relicSets = c.path("relic_sets").map { s ->
                    MihomoRelicSet(name = s.path("name").asText(""), pieces = s.path("num").asInt(0))
                    // The API repeats a 4pc set as separate 2pc+4pc entries; dedupe keeps the max.
                }.groupBy { it.name }.map { (name, entries) -> MihomoRelicSet(name, entries.maxOf { it.pieces }) },
            )
        },
    )

    private fun affix(node: JsonNode): MihomoAffix = MihomoAffix(
        type = node.path("type").asText(""),
        name = node.path("name").asText(""),
        display = node.path("display").asText(""),
    )
}
