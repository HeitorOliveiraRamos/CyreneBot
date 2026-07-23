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
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Access to [nanoka.cc](https://hsr.nanoka.cc)'s public HSR data — a SvelteKit app whose data is
 * plain, versioned JSON on a CDN (`static.nanoka.cc/hsr/<version>/...`), no API key or scraping.
 *
 * Since the Seam-2 cutover, documents are rendered from the V17 tables ([TableKnowledgeSource]),
 * not fetched here — so this class is reduced to what still has live callers:
 *  - [resolveVersion], the live data version (used by [com.cyrene.hsr.SrsNanokaHarvester] to build
 *    its CDN base, by [HsrKnowledgeIngestion] to record what it indexed, and by [KbFreshnessCheck]);
 *  - the pure companion parsers ([fill]/[strip]/[children]/[paramList]/[maxLevelParams]) the harvester
 *    reuses to flatten nanoka JSON, pinned by [NanokaIngestionSourceTest].
 */
@Component
class NanokaIngestionSource(
    private val properties: BotProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * Pinned version if set, else the one embedded in the home page's `hsr/<version>/` URLs.
     * When a version is pinned, [KbFreshnessCheck] compares pin vs indexed (catches "changed the
     * pin, forgot to reindex"), not pin vs live — pinning is an explicit choice to freeze a patch.
     */
    fun resolveVersion(): String? {
        properties.knowledge.nanokaVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val home = getText(properties.knowledge.nanokaHomeUrl) ?: return null
        return VERSION_IN_URL.find(home)?.groupValues?.get(1)
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

        /** `#N[fmt]` with an optional trailing `%`: N is 1-based; fmt is `i` (int) or `fK` (K decimals). */
        private val PLACEHOLDER = Regex("""#(\d+)\[([^\]]+)\](%?)""")

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

        /** `param_list` of the lowest numeric key (e.g. a light cone's superimpose 1). */
        internal fun minLevelParams(levelNode: JsonNode): List<Double> {
            if (!levelNode.isObject || levelNode.isEmpty) return emptyList()
            val minKey = levelNode.fieldNames().asSequence().minByOrNull { it.toIntOrNull() ?: Int.MAX_VALUE } ?: return emptyList()
            return paramList(levelNode.path(minKey).path("param_list"))
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
