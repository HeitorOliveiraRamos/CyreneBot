package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.cyrene.knowledge.NanokaIngestionSource.Companion.fill
import com.cyrene.knowledge.NanokaIngestionSource.Companion.strip
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

/**
 * Builds the PT-BR HSR kit documents from [starrailstation.com](https://starrailstation.com/pt/).
 *
 * The site is a React app whose data is served as plain JSON from an undocumented API. Each file's
 * URL is `{srsDataUrl}{deploymentId}/{hash(locale/path.json)}`, where [hashPath] is the site's own
 * Java-style 31-hash (unsigned, base36) and `deploymentId` is the patch-versioned deploy string
 * embedded in the home-page HTML. No API key, no HTML scraping, no headless browser.
 *
 * This is the PT-BR replacement for the categories nanoka only serves in English — the highest-value,
 * most-queried docs:
 *  - **profile** — name, rarity, Path, element (all from `{loc}/characters.json`).
 *  - **skill** — the 5 canonical abilities (Basic/Skill/Ultimate/Talent/Technique), picked as the
 *    first id of each `skillGrouping` bucket so enhanced-ultimate/technique variants don't duplicate.
 *  - **eidolon** — the 6 `ranks`, in order.
 *  - **trace** — the 3 major traces (A2/A4/A6 Bonus Abilities), the `type == 1` nodes of
 *    `skillTreePoints` (see [majorTraces]).
 *  - **light_cone** — every cone's superimposed passive (from `{loc}/lightcones/{id}.json`).
 *  - **relic_set** — set bonuses, cavern (2/4pc) vs planar ornament (2pc), from `{loc}/relics.json`.
 *
 * [NanokaIngestionSource] still owns **build** and **enemy** docs: starrailstation ships an empty
 * `relicRecommend` and no monster dataset. The two are
 * combined in [HsrKnowledgeIngestion]; the metadata shape (`category` + `name`, plus `character_id`
 * for per-character docs) is identical so `GameKnowledgeTools.lookupHsr` treats both alike.
 *
 * Descriptions carry the same `#N[fmt]%` placeholders as nanoka (filled from a per-level `params`
 * array) and the same inline HoYo/HTML markup — so [fill]/[strip] are reused verbatim.
 *
 * Every fetch fails open (logged + skipped) so one bad URL never aborts a reindex; an empty result
 * signals [HsrKnowledgeIngestion] to fall back to full nanoka rather than wipe a working KB.
 */
@Component
class StarRailStationIngestionSource(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // followRedirects: the home URL 308-redirects `/pt/` → `/pt`, and the JDK client defaults to
    // NEVER — without this every deployment-id fetch dies on the redirect (and any data endpoint
    // that redirects would too).
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * The load result: the documents plus the game-id → PT display-name maps for relic sets and
     * light cones, recorded from the SAME strings the docs carry. [NanokaIngestionSource.buildDoc]
     * renders its recommended-item lists through these maps so a build doc's item names match the
     * PT relic_set/light_cone doc names VERBATIM — the exact-string contract
     * `GameKnowledgeTools.effectDocs` joins on. Ids are the shared game ids (nanoka uses the same).
     */
    data class SrsData(
        val docs: List<Document>,
        val relicNames: Map<String, String>,
        val coneNames: Map<String, String>,
    ) {
        companion object { val EMPTY = SrsData(emptyList(), emptyMap(), emptyMap()) }
    }

    /** Fetches + flattens the PT core datasets into embeddable documents. [SrsData.EMPTY] on hard failure. */
    fun load(): SrsData {
        val deployment = resolveDeployment() ?: run {
            log.error("SRS: could not resolve deployment id (home page {} unreachable and no pinned id). Skipping starrailstation ingest.",
                properties.knowledge.srsHomeUrl)
            return SrsData.EMPTY
        }
        log.info("SRS: ingesting PT-BR HSR data (deployment {}, locale {})", deployment, properties.knowledge.srsLocale)

        val relicNames = mutableMapOf<String, String>()
        val coneNames = mutableMapOf<String, String>()
        val docs = mutableListOf<Document>()
        docs += characters(deployment)
        docs += lightCones(deployment, coneNames)
        docs += relicSets(deployment, relicNames)
        log.info("SRS: built {} PT documents total", docs.size)
        return SrsData(docs, relicNames, coneNames)
    }

    // -------------------- deployment discovery -------------------- //

    /** Pinned deployment id if set, else the one embedded in the home page's global-env JSON. */
    fun resolveDeployment(): String? {
        properties.knowledge.srsDeploymentId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val home = getText(properties.knowledge.srsHomeUrl) ?: return null
        return DEPLOYMENT_IN_HTML.find(home)?.groupValues?.get(1)
    }

    // -------------------- characters -------------------- //

    private fun characters(deployment: String): List<Document> {
        val index = getData(deployment, "characters.json")?.path("entries") ?: return emptyList()
        val docs = mutableListOf<Document>()
        var withKit = 0
        for (entry in index) {
            val name = entry.path("name").asText("").let(::strip)
            val pageId = entry.path("pageId").asText("").trim()
            if (name.isEmpty() || pageId.isEmpty()) continue
            docs += profileDoc(name, pageId, entry)

            val detail = getData(deployment, "characters/$pageId.json") ?: continue
            canonicalSkills(detail).forEach { sk -> skillDoc(name, pageId, sk)?.let { docs += it } }
            detail.path("ranks").forEachIndexed { i, rk -> eidolonDoc(name, pageId, i + 1, rk)?.let { docs += it } }
            majorTraces(detail).forEach { pt -> traceDoc(name, pageId, pt)?.let { docs += it } }
            withKit++
        }
        log.info("SRS: {} character profiles ({} with full kits)", docs.count { it.metadata["category"] == "profile" }, withKit)
        return docs
    }

    private fun profileDoc(name: String, pageId: String, entry: JsonNode): Document {
        val text = buildString {
            append("$name — personagem de Honkai: Star Rail.\n")
            entry.path("rarity").asInt(0).takeIf { it > 0 }?.let { append("Raridade: $it estrelas\n") }
            entry.path("baseType").path("name").asText("").let(::strip).takeIf { it.isNotEmpty() }?.let { append("Caminho (Path): $it\n") }
            entry.path("damageType").path("name").asText("").let(::strip).takeIf { it.isNotEmpty() }?.let { append("Elemento: $it\n") }
        }.trim()
        return Document(text, metaOf("profile", name, pageId))
    }

    private fun skillDoc(charName: String, pageId: String, sk: JsonNode): Document? {
        val skillName = sk.path("name").asText("").let(::strip)
        val rawDesc = sk.path("descHash").asText("")
        if (skillName.isEmpty() && rawDesc.isBlank()) return null
        val desc = fill(rawDesc, maxLevelParams(sk.path("levelData")))
        val type = sk.path("typeDescHash").asText("").let(::strip)
        val header = "$charName — habilidade: $skillName" + if (type.isNotEmpty()) " ($type)" else ""
        return Document(listOf(header, desc).filter { it.isNotBlank() }.joinToString("\n"), metaOf("skill", charName, pageId))
    }

    private fun eidolonDoc(charName: String, pageId: String, n: Int, rk: JsonNode): Document? {
        val rawDesc = rk.path("descHash").asText("").trim()
        if (rawDesc.isEmpty()) return null
        val name = rk.path("name").asText("").let(::strip)
        val desc = fill(rawDesc, paramList(rk.path("params")))
        return Document("$charName — Eidolon $n: $name\n$desc".trim(), metaOf("eidolon", charName, pageId))
    }

    /** Same text shape as [NanokaIngestionSource.traceDoc] — retrieval keys on the `trace` category. */
    private fun traceDoc(charName: String, pageId: String, pt: JsonNode): Document? {
        val bonus = pt.path("embedBonusSkill")
        val rawDesc = bonus.path("descHash").asText("")
        if (rawDesc.isBlank()) return null
        val name = bonus.path("name").asText("").let(::strip)
        val desc = fill(rawDesc, maxLevelParams(bonus.path("levelData")))
        return Document("$charName — traço maior (Bonus Ability): $name\n$desc".trim(), metaOf("trace", charName, pageId))
    }

    // -------------------- light cones -------------------- //

    private fun lightCones(deployment: String, names: MutableMap<String, String>): List<Document> {
        val ids = searchItemIds(deployment, type = 1)
        val docs = mutableListOf<Document>()
        for (id in ids) {
            val lc = getData(deployment, "lightcones/$id.json") ?: continue
            val name = lc.path("name").asText("").let(::strip)
            if (name.isEmpty()) continue
            names[id] = name
            val skill = lc.path("skill")
            val passiveName = skill.path("name").asText("").let(::strip)
            val passiveDesc = fill(skill.path("descHash").asText(""), maxLevelParams(skill.path("levelData")))
            val text = buildString {
                append("$name — Cone de Luz (Light Cone) de Honkai: Star Rail.\n")
                lc.path("rarity").asInt(0).takeIf { it > 0 }?.let { append("Raridade: $it estrelas\n") }
                lc.path("baseType").path("name").asText("").let(::strip).takeIf { it.isNotEmpty() }?.let { append("Caminho (Path): $it\n") }
                if (passiveName.isNotEmpty() || passiveDesc.isNotBlank()) {
                    append("Efeito${if (passiveName.isNotEmpty()) " ($passiveName)" else ""}: $passiveDesc\n")
                }
            }.trim()
            docs += Document(text, metaOf("light_cone", name))
        }
        log.info("SRS: {} light cones", docs.size)
        return docs
    }

    // -------------------- relic / ornament sets -------------------- //

    private fun relicSets(deployment: String, names: MutableMap<String, String>): List<Document> {
        val index = getData(deployment, "relics.json")?.path("entries") ?: return emptyList()
        val docs = mutableListOf<Document>()
        for (set in index) {
            val name = set.path("name").asText("").let(::strip)
            if (name.isEmpty()) continue
            set.path("pageId").asText("").takeIf { it.isNotEmpty() }?.let { names[it] = name }
            val bonuses = set.path("skills").associate { it.path("useNum").asInt() to bonus(it) }
            val two = bonuses[2]
            val four = bonuses[4]
            // A 4-piece bonus means a Cavern relic set; 2-only means a Planar Ornament.
            val kind = if (four != null) "Conjunto de Relíquia (Cavern Relics)" else "Ornamento Planar (Planar Ornament)"
            val text = buildString {
                append("$name — $kind de Honkai: Star Rail.\n")
                two?.takeIf { it.isNotBlank() }?.let { append("Bônus 2 peças: $it\n") }
                four?.takeIf { it.isNotBlank() }?.let { append("Bônus 4 peças: $it\n") }
            }.trim()
            docs += Document(text, metaOf("relic_set", name))
        }
        log.info("SRS: {} relic/ornament sets", docs.size)
        return docs
    }

    private fun bonus(skill: JsonNode): String = fill(skill.path("desc").asText(""), paramList(skill.path("params")))

    // -------------------- shared datasets -------------------- //

    /** Ids of a given `searchItems.json` type (0=character, 1=light cone, 2=relic set). */
    private fun searchItemIds(deployment: String, type: Int): List<String> =
        getData(deployment, "searchItems.json")?.path("entries")
            ?.filter { it.path("type").asInt(-1) == type }
            ?.mapNotNull { it.path("url").asText("").substringAfterLast('/').takeIf(String::isNotEmpty) }
            ?: emptyList()

    // -------------------- mapping helpers -------------------- //

    private fun metaOf(category: String, name: String, pageId: String? = null): Map<String, Any> = buildMap {
        put("category", category)
        put("name", name)
        pageId?.let { put("character_id", it) }
    }

    // -------------------- io -------------------- //

    /** Fetches + parses `{srsDataUrl}{deployment}/{hash(locale/path)}`. Null on any failure. */
    private fun getData(deployment: String, path: String): JsonNode? {
        val locized = "${properties.knowledge.srsLocale}/$path"
        val url = "${properties.knowledge.srsDataUrl.trimEnd('/')}/$deployment/${hashPath(locized)}"
        return getText(url)?.let {
            try {
                mapper.readTree(it)
            } catch (e: Exception) {
                log.warn("SRS: bad JSON from {} ({}): {}", url, locized, e.message)
                null
            }
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
        else { log.warn("SRS: HTTP {} for {}", resp.statusCode(), url); null }
    } catch (e: Exception) {
        log.warn("SRS: fetch failed for {}: {}", url, e.message)
        null
    }

    internal companion object {
        /** `"DEPLOYMENT_ID":"V4.3Live-…"` from the home-page global-env JSON. */
        private val DEPLOYMENT_IN_HTML = Regex(""""DEPLOYMENT_ID":"([^"]+)"""")

        /**
         * starrailstation's own path→file hash: Java-style 31-hash over the chars, kept 32-bit
         * (Kotlin `Int` overflow wraps like `t &= t`), read UNSIGNED, base36. Must byte-match the
         * site's client or every fetch 404s — pinned by [StarRailStationIngestionSourceTest].
         */
        internal fun hashPath(path: String): String {
            var t = 0
            for (c in path) t = (t shl 5) - t + c.code
            return t.toUInt().toString(36)
        }

        /** Values of a JSON array node, in order. */
        internal fun paramList(node: JsonNode): List<Double> =
            if (node.isArray) node.map { it.asDouble() } else emptyList()

        /** `params` of the highest-`level` entry in a `levelData` array (max-level multipliers). */
        internal fun maxLevelParams(levelData: JsonNode): List<Double> {
            if (!levelData.isArray || levelData.isEmpty) return emptyList()
            val top = levelData.maxByOrNull { it.path("level").asInt(0) } ?: return emptyList()
            return paramList(top.path("params"))
        }

        /**
         * The 5 canonical abilities: the first skill id of each `skillGrouping` bucket
         * (Basic/Skill/Ultimate/Talent/Technique), so enhanced-ult and technique-attack
         * variants that share a bucket don't each become a near-duplicate doc. Falls back to
         * the raw `skills` list (deduped by name) when `skillGrouping` is absent.
         */
        /**
         * The 3 major traces (A2/A4/A6 Bonus Abilities): `skillTreePoints` nodes with `type` 1.
         * On older characters two of them sit NESTED under minor stat nodes (e.g. Fu Xuan) —
         * hence the recursive walk. Enhanced-form duplicates (`enhanceId` > 0, e.g. Seele) are
         * dropped, mirroring how [canonicalSkills] keeps only the base kit.
         */
        internal fun majorTraces(detail: JsonNode): List<JsonNode> {
            val out = mutableListOf<JsonNode>()
            fun walk(node: JsonNode) {
                // add(), not +=: JsonNode is Iterable<JsonNode>, so += concat-copies its children.
                if (node.path("type").asInt() == 1 && node.path("enhanceId").asInt(0) == 0) out.add(node)
                node.path("children").forEach { walk(it) }
            }
            detail.path("skillTreePoints").forEach { walk(it) }
            return out
        }

        internal fun canonicalSkills(detail: JsonNode): List<JsonNode> {
            val skills = detail.path("skills")
            val byId = skills.associateBy { it.path("id").asLong() }
            val grouping = detail.path("skillGrouping")
            if (!grouping.isArray || grouping.isEmpty) {
                return skills.distinctBy { it.path("name").asText("") }
            }
            return grouping.mapNotNull { group -> byId[group.path(0).asLong()] }
        }
    }
}
