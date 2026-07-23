package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Access to [starrailstation.com](https://starrailstation.com/pt/)'s undocumented JSON API —
 * files served as `{srsDataUrl}{deploymentId}/{hash(locale/path.json)}`, where [hashPath] is the
 * site's own Java-style 31-hash and `deploymentId` is the patch-versioned deploy string embedded
 * in the home-page HTML. No API key, no scraping, no headless browser.
 *
 * Since the Seam-2 cutover, documents are rendered from the V17 tables ([TableKnowledgeSource]),
 * not fetched here — so this class is reduced to what still has live callers:
 *  - [resolveDeployment], the current deploy id (used by [com.cyrene.hsr.SrsNanokaHarvester]);
 *  - the pure companion helpers ([hashPath]/[canonicalSkills]/[majorTraces]/[paramList]/
 *    [maxLevelParams]) the harvester reuses to fetch + flatten srs JSON, pinned by
 *    [StarRailStationIngestionSourceTest].
 */
@Component
class StarRailStationIngestionSource(
    private val properties: BotProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // followRedirects: the home URL 308-redirects `/pt/` → `/pt`, and the JDK client defaults to
    // NEVER — without this the deployment-id fetch dies on the redirect.
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Pinned deployment id if set, else the one embedded in the home page's global-env JSON. */
    fun resolveDeployment(): String? {
        properties.knowledge.srsDeploymentId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val home = getText(properties.knowledge.srsHomeUrl) ?: return null
        return DEPLOYMENT_IN_HTML.find(home)?.groupValues?.get(1)
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

        /** `params` of the lowest-`level` entry (e.g. a light cone's superimpose 1). */
        internal fun minLevelParams(levelData: JsonNode): List<Double> {
            if (!levelData.isArray || levelData.isEmpty) return emptyList()
            val bottom = levelData.minByOrNull { it.path("level").asInt(0) } ?: return emptyList()
            return paramList(bottom.path("params"))
        }

        /**
         * The 5 canonical abilities: the first skill id of each `skillGrouping` bucket
         * (Basic/Skill/Ultimate/Talent/Technique), so enhanced-ult and technique-attack
         * variants that share a bucket don't each become a near-duplicate. Falls back to the
         * raw `skills` list (deduped by name) when `skillGrouping` is absent.
         */
        internal fun canonicalSkills(detail: JsonNode): List<JsonNode> {
            val skills = detail.path("skills")
            val byId = skills.associateBy { it.path("id").asLong() }
            val grouping = detail.path("skillGrouping")
            if (!grouping.isArray || grouping.isEmpty) {
                return skills.distinctBy { it.path("name").asText("") }
            }
            return grouping.mapNotNull { group -> byId[group.path(0).asLong()] }
        }

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
    }
}
