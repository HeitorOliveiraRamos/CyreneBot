package com.cyrene.hsr

import com.cyrene.config.BotProperties
import com.cyrene.knowledge.NanokaIngestionSource
import com.cyrene.knowledge.NanokaIngestionSource.Companion.children
import com.cyrene.knowledge.NanokaIngestionSource.Companion.fill
import com.cyrene.knowledge.NanokaIngestionSource.Companion.strip
import com.cyrene.knowledge.StarRailStationIngestionSource
import com.cyrene.knowledge.StarRailStationIngestionSource.Companion.canonicalSkills
import com.cyrene.knowledge.StarRailStationIngestionSource.Companion.hashPath
import com.cyrene.knowledge.StarRailStationIngestionSource.Companion.majorTraces
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.cyrene.knowledge.NanokaIngestionSource.Companion.maxLevelParams as nanMaxLevel
import com.cyrene.knowledge.NanokaIngestionSource.Companion.paramList as nanParams
import com.cyrene.knowledge.StarRailStationIngestionSource.Companion.maxLevelParams as srsMaxLevel
import com.cyrene.knowledge.StarRailStationIngestionSource.Companion.paramList as srsParams
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Harvests the full [HsrCharacter] kit rows from the two structured-JSON sources, PT-first:
 *
 *  - **nanoka** ([NanokaIngestionSource]) is the SPINE — its numeric character index lists
 *    every id including betas/leaks srs hasn't published, and each index entry carries `icon`
 *    (the srs page slug) so the two join. Text is English.
 *  - **starrailstation** ([StarRailStationIngestionSource]) is the PT overlay — its index
 *    entry carries `rankKey` (the same numeric id), so for a released character we pull the
 *    Portuguese kit/lore; a character absent from srs keeps nanoka's English (nulls are fine).
 *
 * The two ingestion beans are injected only to reuse their version/deployment discovery and
 * their hardened pure parsers (skill grouping, trace walk, placeholder [fill], markup [strip],
 * the srs path [hashPath]); the per-character fetches are done here so the vector-store
 * pipeline is left untouched. Every extractor is a pure companion function, fixture-tested.
 *
 * Called only from [HsrCharacterService]'s ~monthly staleness check. Returns [] on a hard
 * failure (nanoka spine unreachable) so the caller keeps the previous rows.
 */
@Component
class HsrCharacterHarvester(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
    private val nanoka: NanokaIngestionSource,
    private val srs: StarRailStationIngestionSource,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun harvest(): List<HsrCharacter> {
        val k = properties.knowledge
        val nanoVer = nanoka.resolveVersion() ?: run {
            log.error("hsr_character: nanoka version unresolved — aborting harvest (keeping previous rows).")
            return emptyList()
        }
        val nanoBase = "${k.nanokaCdnUrl.trimEnd('/')}/$nanoVer"
        val index = getJson("$nanoBase/character.json") ?: run {
            log.error("hsr_character: nanoka character index unreachable — aborting harvest.")
            return emptyList()
        }

        val dep = srs.resolveDeployment()
        val srsByRankKey: Map<String, JsonNode> = dep?.let { d ->
            getSrs(d, "characters.json")?.path("entries")
                ?.associateBy { it.path("rankKey").asText("") }
        }.orEmpty()
        if (dep == null || srsByRankKey.isEmpty()) {
            log.warn("hsr_character: starrailstation unavailable — harvesting English-only from nanoka.")
        }

        var withPt = 0
        val rows = index.fields().asSequence().mapNotNull { (id, meta) ->
            val srsEntry = srsByRankKey[id]
            val pageId = (srsEntry?.path("pageId")?.asText("")?.ifBlank { null })
                ?: meta.path("icon").asText("").ifBlank { null }
            val srsDetail = if (dep != null && srsEntry != null && pageId != null) {
                getSrs(dep, "characters/$pageId.json")
            } else {
                null
            }
            if (srsEntry != null) withPt++
            val nanDetail = getJson("$nanoBase/en/character/$id.json")
            buildRow(id, meta, srsEntry, srsDetail, nanDetail)
        }.toList()

        log.info("hsr_character: harvested {} characters ({} with PT from srs, rest English)", rows.size, withPt)
        return rows
    }

    // -------------------- io -------------------- //

    private fun getSrs(deployment: String, path: String): JsonNode? {
        val locized = "${properties.knowledge.srsLocale}/$path"
        val url = "${properties.knowledge.srsDataUrl.trimEnd('/')}/$deployment/${hashPath(locized)}"
        return getJson(url)
    }

    private fun getJson(url: String): JsonNode? = getText(url)?.let {
        try {
            mapper.readTree(it)
        } catch (e: Exception) {
            log.warn("hsr_character: bad JSON from {}: {}", url, e.message)
            null
        }
    }

    private fun getText(url: String): String? = try {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 (compatible; CyreneBot/1.0; +discord)")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() in 200..299) resp.body()
        else { log.warn("hsr_character: HTTP {} for {}", resp.statusCode(), url); null }
    } catch (e: Exception) {
        log.warn("hsr_character: fetch failed for {}: {}", url, e.message)
        null
    }

    internal companion object {
        /** Trailblazer/{NICKNAME} placeholder → English label for the EN name. */
        private const val TRAILBLAZER = "Trailblazer"

        /** HSR internal path codenames → readable EN (nanoka fallback for betas; srs gives PT). */
        private val PATH_EN = mapOf(
            "Warrior" to "Destruction", "Knight" to "Preservation", "Mage" to "Erudition",
            "Shaman" to "Harmony", "Warlock" to "Nihility", "Priest" to "Abundance",
            "Rogue" to "The Hunt", "Memory" to "Remembrance",
        )

        /** nanoka combat types → readable EN. `Thunder` is the internal name for Lightning. */
        private val ELEMENT_EN = mapOf("Thunder" to "Lightning")

        private val RARITY_DIGIT = Regex("""(\d)$""")

        /**
         * Assembles one row PT-first: each field takes the srs (Portuguese) value when present,
         * else the nanoka (English) one. Pure — the whole extraction is fixture-testable without
         * the network. [srsEntry] is the srs index entry (names/element/rarity/path); [srsDetail]
         * and [nanDetail] are the per-character detail JSONs (either may be null).
         */
        internal fun buildRow(
            id: String,
            nanMeta: JsonNode,
            srsEntry: JsonNode?,
            srsDetail: JsonNode?,
            nanDetail: JsonNode?,
        ): HsrCharacter {
            val srsAbil = srsDetail?.let(::srsAbilities).orEmpty()
            val nanAbil = nanDetail?.let(::nanAbilities).orEmpty()
            val traces = srsTraces(srsDetail).ifEmpty { nanTraces(nanDetail) }
            val eidolons = srsEidolons(srsDetail).ifEmpty { nanEidolons(nanDetail) }
            val stories = srsStories(srsDetail).ifEmpty { nanStories(nanDetail) }
            return HsrCharacter(
                id = id,
                nameEn = nanMeta.path("en").asText("").ifBlank { null }?.replace("{NICKNAME}", TRAILBLAZER),
                namePt = srsEntry?.path("name")?.asText("")?.ifBlank { null },
                elemento = srsEntry?.path("damageType")?.path("name")?.asText("")?.ifBlank { null }
                    ?: nanMeta.path("damageType").asText("").ifBlank { null }?.let { ELEMENT_EN[it] ?: it },
                raridade = srsEntry?.path("rarity")?.asInt(0)?.takeIf { it > 0 }
                    ?: RARITY_DIGIT.find(nanMeta.path("rank").asText(""))?.value?.toIntOrNull(),
                caminho = srsEntry?.path("baseType")?.path("name")?.asText("")?.ifBlank { null }
                    ?: nanMeta.path("baseType").asText("").ifBlank { null }?.let { PATH_EN[it] ?: it },
                faccao = srsDetail?.path("archive")?.path("camp")?.asText("")?.ifBlank { null }
                    ?: nanDetail?.path("chara_info")?.path("camp")?.asText("")?.ifBlank { null },
                descricao = srsDetail?.path("descHash")?.asText("")?.ifBlank { null }?.let { fill(it, emptyList()) }
                    ?: nanMeta.path("desc").asText("").ifBlank { null }?.let(::strip),
                atqBasico = srsAbil["ATQ Básico"] ?: nanAbil["Basic ATK"],
                pericia = srsAbil["Perícia"] ?: nanAbil["Skill"],
                periciaSuprema = srsAbil["Perícia Suprema"] ?: nanAbil["Ultimate"],
                talento = srsAbil["Talento"] ?: nanAbil["Talent"],
                tecnica = srsAbil["Técnica"] ?: nanAbil["Technique"],
                tracoA2 = traces.getOrNull(0),
                tracoA4 = traces.getOrNull(1),
                tracoA6 = traces.getOrNull(2),
                eidolon1 = eidolons.getOrNull(0),
                eidolon2 = eidolons.getOrNull(1),
                eidolon3 = eidolons.getOrNull(2),
                eidolon4 = eidolons.getOrNull(3),
                eidolon5 = eidolons.getOrNull(4),
                eidolon6 = eidolons.getOrNull(5),
                detalhesPersonagem = stories.getOrNull(0),
                historiaParte1 = stories.getOrNull(1),
                historiaParte2 = stories.getOrNull(2),
                historiaParte3 = stories.getOrNull(3),
                historiaParte4 = stories.getOrNull(4),
            )
        }

        /** srs canonical abilities keyed by their PT type tag ("Perícia Suprema" …). */
        private fun srsAbilities(detail: JsonNode): Map<String, String> =
            canonicalSkills(detail).mapNotNull { sk ->
                val tag = strip(sk.path("typeDescHash").asText(""))
                if (tag.isBlank()) return@mapNotNull null
                val name = strip(sk.path("name").asText(""))
                val desc = fill(sk.path("descHash").asText(""), srsMaxLevel(sk.path("levelData")))
                tag to headed(name, desc)
            }.toMap()

        /** nanoka abilities keyed by EN type_name; first per type wins (enhanced dupes dropped). */
        private fun nanAbilities(detail: JsonNode): Map<String, String> = buildMap {
            children(detail.path("skills")).forEach { sk ->
                val type = sk.path("type_name").asText("").trim()
                if (type.isBlank() || containsKey(type)) return@forEach
                val name = sk.path("name").asText("").trim()
                val raw = sk.path("desc").asText("").ifBlank { sk.path("simple_desc").asText("") }
                put(type, headed(strip(name), fill(raw, nanMaxLevel(sk.path("level")))))
            }
        }

        /** The 3 major traces (A2/A4/A6) as rendered text, in tree order. */
        private fun srsTraces(detail: JsonNode?): List<String> =
            detail?.let(::majorTraces)?.mapNotNull { pt ->
                val bonus = pt.path("embedBonusSkill")
                val desc = fill(bonus.path("descHash").asText(""), srsMaxLevel(bonus.path("levelData")))
                headed(strip(bonus.path("name").asText("")), desc).ifBlank { null }
            }.orEmpty()

        private fun nanTraces(detail: JsonNode?): List<String> =
            detail?.let { children(it.path("skill_trees")) }.orEmpty().mapNotNull { pt ->
                val node = pt.path("1")
                val raw = node.path("point_desc").asText("").trim()
                if (raw.isBlank()) return@mapNotNull null
                headed(strip(node.path("point_name").asText("")), fill(raw, nanParams(node.path("param_list"))))
            }

        private fun srsEidolons(detail: JsonNode?): List<String> =
            detail?.let { children(it.path("ranks")) }.orEmpty().map { rk ->
                headed(strip(rk.path("name").asText("")), fill(rk.path("descHash").asText(""), srsParams(rk.path("params"))))
            }

        private fun nanEidolons(detail: JsonNode?): List<String> =
            detail?.let { children(it.path("ranks")) }.orEmpty().map { rk ->
                headed(strip(rk.path("name").asText("")), fill(rk.path("desc").asText(""), nanParams(rk.path("param_list"))))
            }

        /**
         * srs stories as [details, parte1..4]: the "Detalhes de Personagem" item first (empty
         * string if absent so the parts keep their slots), then the "Parte …" items in order.
         */
        private fun srsStories(detail: JsonNode?): List<String> {
            val items = detail?.let { children(it.path("storyItems")) }.orEmpty()
            if (items.isEmpty()) return emptyList()
            val details = items.firstOrNull { it.path("title").asText("").startsWith("Detalhes") }
            val parts = items.filter { it.path("title").asText("").contains("Parte") }
            return (listOf(details) + parts).map { strip(it?.path("text")?.asText("") ?: "") }
        }

        /** nanoka `chara_info.stories` object keyed "0".."4" → [details, parte1..4]. */
        private fun nanStories(detail: JsonNode?): List<String> {
            val stories = detail?.path("chara_info")?.path("stories") ?: return emptyList()
            if (stories.isMissingNode || (0..4).all { stories.path(it.toString()).asText("").isBlank() }) return emptyList()
            return (0..4).map { strip(stories.path(it.toString()).asText("")) }
        }

        /** "Name\ndescription", or just one when the other is blank; "" when both are. */
        private fun headed(name: String, desc: String): String =
            listOf(name, desc).filter { it.isNotBlank() }.joinToString("\n")
    }
}
