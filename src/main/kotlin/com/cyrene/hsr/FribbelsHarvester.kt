package com.cyrene.hsr

import com.cyrene.config.BotProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One-shot harvest of the `hsr_build_meta` cache (game id → [FribbelsMeta]) from:
 *
 *  - fribbels/hsr-optimizer: per-character build metadata regex-parsed out of the
 *    machine-formatted TypeScript configs (`scoring().stats/parts`, `simulation()`
 *    relic/ornament sets and substat priority). Spread constants (generic alternative
 *    sets) and teammates/rotations are deliberately ignored — only the explicit,
 *    character-specific recommendation is kept;
 *  - StarRailRes (Mar-7th): only the en→pt relic-set name mapping, to render set names in PT.
 *
 * Names and full kit text come from the V17 tables ([PersonagemHsr]); this source is purely the
 * `/build` scoring fallback. Called only from [HsrCharacterService]'s scheduled staleness
 * check (~monthly). Shared fetches throw on failure so the caller keeps the previous rows;
 * a single unparsable character file is skipped with a warning.
 */
@Component
class FribbelsHarvester(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        // The multi-MB git-tree response gets RST_STREAM-cancelled mid-body on HTTP/2
        // (JDK client + GitHub API); HTTP/1.1 streams it reliably and this client only
        // ever does a monthly batch, so multiplexing buys nothing.
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /** Game id → fribbels build metadata. Empty ids (the `*B1.ts` reruns with no numeric config
     *  id) and unparsable configs are skipped; names/kit come from the V17 tables. */
    fun harvest(): Map<String, FribbelsMeta> {
        val srr = properties.knowledge.starRailResBase
        val setPtByEn = relicSetPtByEnName(srr)

        val raw = properties.knowledge.fribbelsRawBase
        val setsEnum = parseSetsEnum(fetch(raw + "src/lib/constants/constants.ts"))
        val paths = characterPaths(fetch(properties.knowledge.fribbelsTreeUrl))
        log.info("fribbels: harvesting {} character configs", paths.size)

        return paths.mapNotNull { path ->
            try {
                parseCharacter(fetch(raw + path))
                    // Expected for the *B1.ts enhanced-rerun variants (id '1005b1'): mihomo
                    // always reports the base game id, so their metadata has no join key.
                    ?: run { log.info("fribbels: no numeric config id in {} — skipped", path); null }
            } catch (e: Exception) {
                log.warn("fribbels: failed to fetch/parse {} — skipped: {}", path, e.message)
                null
            }
        }.associate { it.id to it.toMeta(setsEnum, setPtByEn) }
    }

    private fun relicSetPtByEnName(base: String): Map<String, String> {
        val en = mapper.readTree(fetch("${base}en/relic_sets.json"))
        val pt = mapper.readTree(fetch("${base}pt/relic_sets.json"))
        return buildMap {
            en.fields().forEach { (id, node) ->
                val ptName = pt.path(id).path("name").asText("")
                if (ptName.isNotBlank()) put(node.path("name").asText(""), ptName)
            }
        }
    }

    private fun characterPaths(treeJson: String): List<String> =
        mapper.readTree(treeJson).path("tree")
            .map { it.path("path").asText("") }
            .filter { it.startsWith("src/lib/conditionals/character/") && it.endsWith(".ts") }

    private fun fetch(url: String): String {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 (compatible; CyreneBot/1.0; +discord)")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()} for $url" }
        return checkNotNull(resp.body()) { "corpo vazio para $url" }
    }

    internal companion object {

        /** fribbels `Stats.*` enum key → game property key (mihomo/StarRailScore vocabulary). */
        internal val STAT_PROPERTY = mapOf(
            "HP" to "HPDelta", "ATK" to "AttackDelta", "DEF" to "DefenceDelta",
            "HP_P" to "HPAddedRatio", "ATK_P" to "AttackAddedRatio", "DEF_P" to "DefenceAddedRatio",
            "SPD" to "SpeedDelta", "CR" to "CriticalChanceBase", "CD" to "CriticalDamageBase",
            "EHR" to "StatusProbabilityBase", "RES" to "StatusResistanceBase",
            "BE" to "BreakDamageAddedRatioBase", "ERR" to "SPRatioBase", "OHB" to "HealRatioBase",
            "Physical_DMG" to "PhysicalAddedRatio", "Fire_DMG" to "FireAddedRatio",
            "Ice_DMG" to "IceAddedRatio",
            // yes, Thunder — the game's internal key for Lightning damage.
            "Lightning_DMG" to "ThunderAddedRatio",
            "Wind_DMG" to "WindAddedRatio", "Quantum_DMG" to "QuantumAddedRatio",
            "Imaginary_DMG" to "ImaginaryAddedRatio",
        )

        /** fribbels `Parts.*` enum key → mihomo relic slot (1–6; head/hands have fixed mains). */
        internal val PART_SLOT = mapOf(
            "Head" to 1, "Hands" to 2, "Body" to 3, "Feet" to 4, "PlanarSphere" to 5, "LinkRope" to 6,
        )

        private val ID_RE = Regex("\\n\\s*id: '(\\d+)'")
        private val STAT_WEIGHT_RE = Regex("\\[Stats\\.(\\w+)]: ([0-9.]+)")
        private val PART_RE = Regex("\\[Parts\\.(\\w+)]: \\[([^]]*)]")
        private val STAT_REF_RE = Regex("Stats\\.(\\w+)")
        private val SET_PAIR_RE = Regex("\\[\\s*Sets\\.(\\w+),\\s*Sets\\.(\\w+)")
        private val SET_REF_RE = Regex("Sets\\.(\\w+)")
        private val SET_ENUM_RE = Regex("(\\w+): '((?:\\\\'|[^'])*)'")

        internal data class ParsedChar(
            val id: String,
            /** Substat weights keyed by fribbels `Stats.*` enum key. */
            val stats: Map<String, Double>,
            /** Ideal main stats per `Parts.*` enum key, as `Stats.*` keys. */
            val parts: Map<String, List<String>>,
            /** Recommended relic-set pairs / ornament sets as `Sets.*` enum keys. */
            val relicSets: List<Pair<String, String>>,
            val ornamentSets: List<String>,
            /** Substat priority order as `Stats.*` keys. */
            val substats: List<String>,
        ) {
            fun toMeta(setsEnum: Map<String, String>, setPtByEn: Map<String, String>): FribbelsMeta {
                fun setName(enumKey: String): String =
                    setsEnum[enumKey]?.let { en -> setPtByEn[en] ?: en } ?: enumKey
                return FribbelsMeta(
                    subWeights = stats.mapNotNull { (k, w) -> STAT_PROPERTY[k]?.let { it to w } }.toMap(),
                    mainStats = parts.mapNotNull { (part, statKeys) ->
                        PART_SLOT[part]?.let { slot -> slot to statKeys.mapNotNull { STAT_PROPERTY[it] } }
                    }.toMap().filterValues { it.isNotEmpty() },
                    relicSets = relicSets.map { (a, b) -> listOf(setName(a), setName(b)) },
                    ornamentSets = ornamentSets.map { setName(it) },
                    substatPriority = substats.mapNotNull { STAT_PROPERTY[it] },
                )
            }
        }

        /**
         * Pure regex parse of one machine-formatted character config. Anchors on the
         * `const scoring` / `const simulation` blocks; a repo-side format change makes this
         * return empty fields, never wrong ones — the service's sanity floor catches that.
         */
        internal fun parseCharacter(ts: String): ParsedChar? {
            val id = ID_RE.findAll(ts).lastOrNull()?.groupValues?.get(1) ?: return null
            val scoring = ts.substringAfterLast("const scoring")
            val sim = ts.substringAfter("const simulation", "").substringBefore("const scoring")
            return ParsedChar(
                id = id,
                stats = STAT_WEIGHT_RE.findAll(slice(scoring, "stats:", '{', '}'))
                    .associate { it.groupValues[1] to it.groupValues[2].toDouble() },
                parts = PART_RE.findAll(slice(scoring, "parts:", '{', '}'))
                    .associate { m ->
                        m.groupValues[1] to STAT_REF_RE.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
                    },
                relicSets = SET_PAIR_RE.findAll(slice(sim, "relicSets:", '[', ']'))
                    .map { it.groupValues[1] to it.groupValues[2] }.toList(),
                ornamentSets = SET_REF_RE.findAll(slice(sim, "ornamentSets:", '[', ']'))
                    .map { it.groupValues[1] }.toList(),
                substats = STAT_REF_RE.findAll(slice(sim, "substats:", '[', ']'))
                    .map { it.groupValues[1] }.toList(),
            )
        }

        /** `Sets.*` enum key → English in-game set name, from fribbels constants.ts. */
        internal fun parseSetsEnum(constantsTs: String): Map<String, String> =
            SET_ENUM_RE.findAll(slice(constantsTs, "export const Sets =", '{', '}'))
                .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

        /** Content between the balanced [open]/[close] pair that follows [marker]; "" if absent. */
        internal fun slice(text: String, marker: String, open: Char, close: Char): String {
            val at = text.indexOf(marker)
            if (at < 0) return ""
            val start = text.indexOf(open, at)
            if (start < 0) return ""
            var depth = 0
            for (i in start until text.length) {
                when (text[i]) {
                    open -> depth++
                    close -> if (--depth == 0) return text.substring(start + 1, i)
                }
            }
            return ""
        }
    }
}
