package com.cyrene.hsr

import com.cyrene.config.BotProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Per-character scoring weights for the build analyzer, from the community-standard
 * StarRailScore table (`score.json`, Mar-7th — same ids as mihomo):
 *
 *  - [CharWeights.main]: slot (1–6) → main-stat property → weight 0..1;
 *  - [CharWeights.weight]: substat property → weight 0..1;
 *  - [CharWeights.max]: normalization constant for the substat raw score.
 *
 * Loaded on startup and refreshed daily; a failed refresh keeps the last good table, and an
 * unknown character id simply returns null so the caller abstains from judging instead of
 * guessing (new characters can lag a patch behind in the community table).
 */
@Component
class ScoreWeights(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    data class CharWeights(
        val main: Map<Int, Map<String, Double>>,
        val weight: Map<String, Double>,
        val max: Double,
    )

    @Volatile
    private var table: Map<String, CharWeights> = emptyMap()

    fun forCharacter(id: String): CharWeights? {
        if (table.isEmpty()) refresh()
        return table[id]
    }

    /** Loads on startup, then daily — the table changes at most once per patch. */
    @Scheduled(initialDelay = 0, fixedDelay = 24 * 60, timeUnit = TimeUnit.MINUTES)
    @Synchronized
    fun refresh() {
        try {
            val req = HttpRequest.newBuilder(URI.create(properties.knowledge.scoreJsonUrl))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (compatible; CyreneBot/1.0; +discord)")
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("StarRailScore: HTTP {} — keeping previous table ({} chars)", resp.statusCode(), table.size)
                return
            }
            val parsed = parse(mapper.readTree(resp.body()))
            if (parsed.isEmpty()) {
                log.warn("StarRailScore: parsed 0 entries — keeping previous table")
                return
            }
            table = parsed
            log.info("StarRailScore: weight table loaded for {} characters", parsed.size)
        } catch (e: Exception) {
            log.warn("StarRailScore: refresh failed — keeping previous table ({} chars): {}", table.size, e.message)
        }
    }

    internal companion object {
        /** Pure JSON→table mapping, testable from a fixture. */
        internal fun parse(root: JsonNode): Map<String, CharWeights> = buildMap {
            root.fields().forEach { (id, node) ->
                val main = buildMap<Int, Map<String, Double>> {
                    node.path("main").fields().forEach { (slot, stats) ->
                        slot.toIntOrNull()?.let { put(it, stats.toWeightMap()) }
                    }
                }
                val weight = node.path("weight").toWeightMap()
                val max = node.path("max").asDouble(0.0)
                if (max > 0) put(id, CharWeights(main, weight, max))
            }
        }

        private fun JsonNode.toWeightMap(): Map<String, Double> = buildMap {
            fields().forEach { (stat, w) -> put(stat, w.asDouble(0.0)) }
        }
    }
}
