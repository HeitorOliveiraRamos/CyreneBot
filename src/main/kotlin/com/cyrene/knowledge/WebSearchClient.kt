package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * One web search result, flattened to what the LLM actually needs.
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

/**
 * Online search fallback for HSR questions the local knowledge base can't answer
 * (new patches, just-released characters, live events). Backed by a self-hosted
 * [SearXNG](https://docs.searxng.org/) instance — no API key, fully private, and it
 * aggregates many engines.
 *
 * Stand one up with Docker, then set `bot.knowledge.searxng-url` (or the `SEARXNG_URL`
 * env var) to its base URL. SearXNG must have the JSON format enabled in its
 * `settings.yml` (`search.formats: [html, json]`).
 *
 * When no URL is configured [isEnabled] is false and [search] returns empty, so the
 * `searchWeb` tool degrades to a clean "web search not configured" message instead of
 * throwing — the rest of the bot is unaffected.
 */
@Component
class WebSearchClient(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val baseUrl: String? = properties.knowledge.searxngUrl?.trim()?.takeIf { it.isNotEmpty() }

    val isEnabled: Boolean get() = baseUrl != null

    /**
     * Runs a search scoped to Honkai: Star Rail. Returns up to [limit] results, or an empty
     * list when web search is disabled or the request fails (failures are logged, never
     * thrown — a flaky search must not break a chat turn).
     */
    fun search(query: String, limit: Int = 5): List<WebSearchResult> {
        val root = baseUrl ?: return emptyList()
        val scoped = "Honkai Star Rail $query"
        val uri = URI.create(
            "$root/search?q=${URLEncoder.encode(scoped, StandardCharsets.UTF_8)}&format=json&safesearch=0",
        )
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(12))
            .header("Accept", "application/json")
            // Some SearXNG deployments reject requests without a UA.
            .header("User-Agent", "CyreneBot/1.0 (+discord)")
            .GET()
            .build()

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                log.warn("SearXNG returned HTTP {} for query '{}'", response.statusCode(), query)
                return emptyList()
            }
            parse(response.body(), limit)
        } catch (e: Exception) {
            log.warn("Web search failed for query '{}': {}", query, e.message)
            emptyList()
        }
    }

    private fun parse(body: String, limit: Int): List<WebSearchResult> {
        val results: JsonNode = mapper.readTree(body).path("results")
        if (!results.isArray) return emptyList()
        return results.take(limit).map { node ->
            WebSearchResult(
                title = node.path("title").asText("").trim(),
                url = node.path("url").asText("").trim(),
                snippet = node.path("content").asText("").trim(),
            )
        }.filter { it.url.isNotEmpty() }
    }
}
