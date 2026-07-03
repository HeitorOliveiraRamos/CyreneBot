package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds the HSR knowledge documents from [nanoka.cc](https://hsr.nanoka.cc)'s public data.
 *
 * nanoka is a SvelteKit app whose data is plain, versioned JSON served from a CDN
 * (`static.nanoka.cc/hsr/<version>/...`) — no API key, no HTML scraping, no headless browser.
 * The home page embeds the live data version, so a reindex always targets the current patch
 * unless [BotProperties.Knowledge.nanokaVersion] pins one.
 *
 * Four datasets are flattened into self-contained [Document]s, mirroring the metadata shape
 * (`category` + `name`) that `GameKnowledgeTools.lookupHsr` expects:
 *  - **profile** / **skill** / **eidolon** — per character (kit from `en/character/{id}.json`).
 *  - **relic_set** — relic & planar-ornament set bonuses (`relicset.json`).
 *  - **enemy** — monsters with weaknesses (`monster.json`).
 *  - **light_cone** — light cones with their superimposed passive (`en/lightcone/{id}.json`).
 *
 * Ability descriptions carry `#N[fmt]%` placeholders filled from a per-level `param_list`;
 * [fill] substitutes the max-level values so the model sees real multipliers, not `#1[i]%`.
 *
 * Intentionally skipped (kept lazy / low-noise): trace trees (mostly null text + internal ids),
 * voicelines/stories (bulk lore that dilutes kit retrieval), numeric stat tables, and the
 * recommended-build id lists (ambiguous id→name mapping — add once verified). The character's
 * short bio is kept as lore-lite. Failures on any one fetch are logged and skipped so a single
 * bad URL never aborts the whole reindex.
 */
@Component
class NanokaIngestionSource(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** Fetches + flattens every dataset into embeddable documents. Empty on hard failure. */
    fun load(): List<Document> {
        val version = resolveVersion() ?: run {
            log.error("Nanoka: could not resolve data version (home page {} unreachable and no pinned version). Aborting nanoka ingest.",
                properties.knowledge.nanokaHomeUrl)
            return emptyList()
        }
        val base = "${properties.knowledge.nanokaCdnUrl.trimEnd('/')}/$version"
        log.info("Nanoka: ingesting HSR data version {} from {}", version, base)

        val docs = mutableListOf<Document>()
        docs += characters(base)
        docs += relicSets(base)
        docs += enemies(base)
        docs += lightCones(base)
        log.info("Nanoka: built {} documents total", docs.size)
        return docs
    }

    // -------------------- version discovery -------------------- //

    /**
     * Pinned version if set, else the one embedded in the home page's `hsr/<version>/` URLs.
     * Public so [HsrKnowledgeIngestion] can record what it indexed and [KbFreshnessCheck]
     * can compare it against that record later. When a version is pinned, the freshness
     * check compares pin vs indexed (catches "changed the pin, forgot to reindex"), not
     * pin vs live — pinning is an explicit choice to freeze a patch.
     */
    fun resolveVersion(): String? {
        properties.knowledge.nanokaVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val home = getText(properties.knowledge.nanokaHomeUrl) ?: return null
        return VERSION_IN_URL.find(home)?.groupValues?.get(1)
    }

    // -------------------- characters -------------------- //

    private fun characters(base: String): List<Document> {
        val index = getJson("$base/character.json") ?: return emptyList()
        val docs = mutableListOf<Document>()
        var withKit = 0
        for ((id, meta) in index.fields()) {
            val name = meta.path("en").asText("").trim()
            if (name.isEmpty()) continue
            docs += profileDoc(id, name, meta)

            val detail = getJson("$base/en/character/$id.json") ?: continue
            children(detail.path("skills")).forEach { sk -> skillDoc(name, sk)?.let { docs += it } }
            children(detail.path("ranks")).forEachIndexed { i, rk -> eidolonDoc(name, i + 1, rk)?.let { docs += it } }
            withKit++
        }
        log.info("Nanoka: {} character profiles ({} with full kits)", index.size(), withKit)
        return docs
    }

    private fun profileDoc(id: String, name: String, meta: JsonNode): Document {
        val text = buildString {
            append("$name — personagem de Honkai: Star Rail.\n")
            rarity(meta.path("rank").asText(""))?.let { append("Raridade: $it estrelas\n") }
            path(meta.path("baseType").asText(""))?.let { append("Caminho (Path): $it\n") }
            element(meta.path("damageType").asText(""))?.let { append("Elemento: $it\n") }
            strip(meta.path("desc").asText("")).takeIf { it.isNotBlank() }?.let { append("Descrição: $it\n") }
        }.trim()
        return Document(text, metaOf("profile", name, id))
    }

    private fun skillDoc(charName: String, sk: JsonNode): Document? {
        val skillName = sk.path("name").asText("").trim()
        val rawDesc = sk.path("desc").asText("").ifBlank { sk.path("simple_desc").asText("") }
        if (skillName.isEmpty() && rawDesc.isBlank()) return null
        val desc = fill(rawDesc, maxLevelParams(sk.path("level")))
        val type = sk.path("type_name").asText("").trim()
        val header = "$charName — habilidade: $skillName" + if (type.isNotEmpty()) " ($type)" else ""
        return Document(listOf(header, desc).filter { it.isNotBlank() }.joinToString("\n"), metaOf("skill", charName))
    }

    private fun eidolonDoc(charName: String, n: Int, rk: JsonNode): Document? {
        val rawDesc = rk.path("desc").asText("").trim()
        if (rawDesc.isEmpty()) return null
        val name = rk.path("name").asText("").trim()
        val desc = fill(rawDesc, paramList(rk.path("param_list")))
        return Document("$charName — Eidolon $n: $name\n$desc".trim(), metaOf("eidolon", charName))
    }

    // -------------------- relic / ornament sets -------------------- //

    private fun relicSets(base: String): List<Document> {
        val sets = getJson("$base/relicset.json") ?: return emptyList()
        val docs = mutableListOf<Document>()
        for ((_, s) in sets.fields()) {
            val name = s.path("en").asText("").trim()
            if (name.isEmpty()) continue
            val setNode = s.path("set")
            val two = bonus(setNode.path("2"))
            val four = bonus(setNode.path("4"))
            // A "4" bonus means a 4-piece Cavern relic set; 2-only means a Planar Ornament.
            val kind = if (setNode.has("4")) "Conjunto de Relíquia (Cavern Relics)" else "Ornamento Planar (Planar Ornament)"
            val text = buildString {
                append("$name — $kind de Honkai: Star Rail.\n")
                two?.let { append("Bônus 2 peças: $it\n") }
                four?.let { append("Bônus 4 peças: $it\n") }
            }.trim()
            docs += Document(text, metaOf("relic_set", name))
        }
        log.info("Nanoka: {} relic/ornament sets", docs.size)
        return docs
    }

    private fun bonus(node: JsonNode): String? {
        val d = node.path("en").asText("").trim()
        if (d.isEmpty()) return null
        // Relic sets use PascalCase `ParamList` (vs `param_list` everywhere else).
        return fill(d, paramList(node.path("ParamList")))
    }

    // -------------------- enemies -------------------- //

    private fun enemies(base: String): List<Document> {
        val mon = getJson("$base/monster.json") ?: return emptyList()
        // The file has many near-duplicate child/minion variants sharing a name; keep one per name.
        val byName = LinkedHashMap<String, Document>()
        for ((_, m) in mon.fields()) {
            val name = m.path("en").asText("").trim()
            if (name.isEmpty()) continue
            val desc = strip(m.path("desc").asText(""))
            val weak = m.path("weak").mapNotNull { element(it.asText(""))?.substringBefore(" (") }
            if (desc.isBlank() && weak.isEmpty()) continue
            val text = buildString {
                append("$name — inimigo de Honkai: Star Rail.\n")
                if (weak.isNotEmpty()) append("Fraquezas (Weaknesses): ${weak.joinToString(", ")}\n")
                if (desc.isNotBlank()) append("Descrição: $desc\n")
            }.trim()
            byName.putIfAbsent(name.lowercase(), Document(text, metaOf("enemy", name)))
        }
        log.info("Nanoka: {} enemies", byName.size)
        return byName.values.toList()
    }

    // -------------------- light cones -------------------- //

    private fun lightCones(base: String): List<Document> {
        val index = getJson("$base/lightcone.json") ?: return emptyList()
        val docs = mutableListOf<Document>()
        for ((id, meta) in index.fields()) {
            val name = meta.path("en").asText("").trim()
            if (name.isEmpty()) continue
            val ref = getJson("$base/en/lightcone/$id.json")?.path("refinements")
            val passiveName = ref?.path("name")?.asText("")?.trim().orEmpty()
            val passiveDesc = ref?.let { fill(it.path("desc").asText(""), maxLevelParams(it.path("level"))) }.orEmpty()
            val text = buildString {
                append("$name — Cone de Luz (Light Cone) de Honkai: Star Rail.\n")
                rarity(meta.path("rank").asText(""))?.let { append("Raridade: $it estrelas\n") }
                path(meta.path("baseType").asText(""))?.let { append("Caminho (Path): $it\n") }
                if (passiveName.isNotEmpty() || passiveDesc.isNotBlank()) {
                    append("Efeito${if (passiveName.isNotEmpty()) " ($passiveName)" else ""}: $passiveDesc\n")
                }
            }.trim()
            docs += Document(text, metaOf("light_cone", name))
        }
        log.info("Nanoka: {} light cones", docs.size)
        return docs
    }

    // -------------------- mapping helpers -------------------- //

    private fun metaOf(category: String, name: String, id: String? = null): Map<String, Any> = buildMap {
        put("category", category)
        put("name", name)
        id?.let { put("character_id", it) }
    }

    private fun rarity(rank: String): String? = RARITY_DIGIT.find(rank)?.value

    private fun path(baseType: String): String? = PATHS[baseType] ?: baseType.takeIf { it.isNotBlank() }

    private fun element(damageType: String): String? = ELEMENTS[damageType] ?: damageType.takeIf { it.isNotBlank() }

    // -------------------- io -------------------- //

    private fun getJson(url: String): JsonNode? = getText(url)?.let {
        try {
            mapper.readTree(it)
        } catch (e: Exception) {
            log.warn("Nanoka: bad JSON from {}: {}", url, e.message)
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
        else { log.warn("Nanoka: HTTP {} for {}", resp.statusCode(), url); null }
    } catch (e: Exception) {
        log.warn("Nanoka: fetch failed for {}: {}", url, e.message)
        null
    }

    internal companion object {
        private val VERSION_IN_URL = Regex("""hsr/(\d+\.\d+\.\d+)/""")
        private val RARITY_DIGIT = Regex("""(\d)$""")

        /** `#N[fmt]` with an optional trailing `%`: N is 1-based; fmt is `i` (int) or `fK` (K decimals). */
        private val PLACEHOLDER = Regex("""#(\d+)\[([^\]]+)\](%?)""")

        /** HSR's internal Path codenames → readable PT (EN) labels. */
        private val PATHS = mapOf(
            "Warrior" to "Destruição (Destruction)", "Rogue" to "Caça (The Hunt)",
            "Mage" to "Erudição (Erudition)", "Shaman" to "Harmonia (Harmony)",
            "Warlock" to "Inexistência (Nihility)", "Knight" to "Preservação (Preservation)",
            "Priest" to "Abundância (Abundance)", "Memory" to "Recordação (Remembrance)",
        )

        /** Combat types → PT (EN). `Thunder` is the internal name for the Lightning element. */
        private val ELEMENTS = mapOf(
            "Physical" to "Físico (Physical)", "Fire" to "Fogo (Fire)", "Ice" to "Gelo (Ice)",
            "Thunder" to "Raio (Lightning)", "Wind" to "Vento (Wind)", "Quantum" to "Quântico (Quantum)",
            "Imaginary" to "Imaginário (Imaginary)",
        )

        /** Child values of an array OR object node, in order. */
        internal fun children(node: JsonNode): List<JsonNode> = node.elements().asSequence().toList()

        internal fun paramList(node: JsonNode): List<Double> =
            if (node.isArray) node.map { it.asDouble() } else emptyList()

        /** `param_list` of the highest numeric key under a per-level `level` object. */
        internal fun maxLevelParams(levelNode: JsonNode): List<Double> {
            if (!levelNode.isObject || levelNode.isEmpty) return emptyList()
            val maxKey = levelNode.fieldNames().asSequence().maxByOrNull { it.toIntOrNull() ?: -1 } ?: return emptyList()
            return paramList(levelNode.path(maxKey).path("param_list"))
        }

        /**
         * Substitutes `#N[fmt]%` placeholders with values from [params] and strips HSR markup.
         * A trailing `%` means the param is a ratio shown as a percent (×100). Unmatched
         * placeholders are left as-is rather than dropped, so a data gap is visible, not silent.
         */
        internal fun fill(desc: String, params: List<Double>): String {
            val filled = PLACEHOLDER.replace(desc) { m ->
                val idx = m.groupValues[1].toInt() - 1
                val raw = params.getOrNull(idx) ?: return@replace m.value
                val isPct = m.groupValues[3] == "%"
                val v = if (isPct) raw * 100 else raw
                val fmt = m.groupValues[2]
                val s = if (fmt.startsWith("f")) {
                    String.format(Locale.US, "%.${fmt.drop(1).toIntOrNull() ?: 1}f", v)
                } else {
                    v.roundToInt().toString() // "i" and any unknown format → integer
                }
                if (isPct) "$s%" else s
            }
            return strip(filled)
        }

        /** Removes HTML/HSR tags (`<color>`, `<unbreak>`…) and `{TOKEN}`s, then collapses whitespace. */
        internal fun strip(s: String): String = s
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("""\{[^}]*}"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
