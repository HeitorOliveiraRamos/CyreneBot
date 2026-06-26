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
 *
 * [snippet] is the short SearXNG preview (1–2 sentences). [content] is the full readable
 * text extracted from the page itself — empty unless this result was among the top
 * `web-fetch-pages` and the fetch succeeded. The page text is what carries a complete kit;
 * a snippet never does.
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val content: String = "",
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
            enrichWithPageText(parse(response.body(), limit))
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

    /**
     * Opens the top [BotProperties.Knowledge.webFetchPages] results and fills in
     * [WebSearchResult.content] with their readable page text. This is the difference
     * between the model seeing a one-line snippet and seeing the actual kit table. Fetches
     * are best-effort and sequential (a handful of pages, modest payoff from parallelism vs.
     * the added complexity); any page that times out or isn't HTML is simply left snippet-only.
     */
    private fun enrichWithPageText(results: List<WebSearchResult>): List<WebSearchResult> {
        val pages = properties.knowledge.webFetchPages
        if (pages <= 0 || results.isEmpty()) return results
        val charLimit = properties.knowledge.webFetchCharLimit
        return results.mapIndexed { i, r ->
            if (i >= pages) return@mapIndexed r
            val text = fetchReadableText(r.url, charLimit)
            if (text.isNotBlank()) r.copy(content = text) else r
        }
    }

    /** Downloads [url] and returns its readable text (tags stripped), capped at [charLimit]. */
    private fun fetchReadableText(url: String, charLimit: Int): String {
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                // A real-browser UA: many fan wikis 403 the default Java UA.
                .header("User-Agent", "Mozilla/5.0 (compatible; CyreneBot/1.0; +discord)")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                log.debug("Page fetch HTTP {} for {}", response.statusCode(), url)
                return ""
            }
            val contentType = response.headers().firstValue("content-type").orElse("")
            if (contentType.isNotEmpty() && !contentType.contains("html", ignoreCase = true)) return ""
            val body = response.body().let { if (it.length > MAX_HTML_CHARS) it.substring(0, MAX_HTML_CHARS) else it }
            htmlToText(body).take(charLimit)
        } catch (e: Exception) {
            log.debug("Page fetch failed for {}: {}", url, e.message)
            ""
        }
    }

    /**
     * Minimal HTML→text reduction with no external dependency: drop non-content blocks
     * (scripts, nav, etc.), turn structural tags into line breaks, strip the rest, decode
     * the few entities that show up in kit text, and collapse whitespace. Not a real DOM
     * parse — it leaves some menu/footer noise — but it surfaces the ability text, which is
     * all the brain needs to reconstruct a kit. (Swap in jsoup later if cleaner output matters.)
     */
    private fun htmlToText(html: String): String {
        var s = STRIP_BLOCKS.replace(html, " ")
        s = BLOCK_BREAK.replace(s, "\n")
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        s = s.replace(INLINE_WS, " ")
        s = s.replace(LINE_LEADING_WS, "\n")
        s = s.replace(BLANK_LINES, "\n\n")
        return s.trim()
    }

    private fun decodeEntities(s: String): String {
        var r = s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        r = NUMERIC_ENTITY.replace(r) { m ->
            m.groupValues[1].toIntOrNull()
                ?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrDefault("") }
                ?: m.value
        }
        return r
    }

    private companion object {
        /** Hard cap on raw HTML processed, so a pathological page can't make the regexes crawl. */
        const val MAX_HTML_CHARS = 500_000

        // (?is) = dot-matches-newline + case-insensitive. Backreference \1 closes the same tag.
        val STRIP_BLOCKS = Regex("(?is)<(script|style|noscript|svg|head|nav|footer|form|template)\\b[^>]*>.*?</\\1>")
        val BLOCK_BREAK = Regex("(?i)</?(p|div|br|li|tr|h[1-6]|section|article|table|ul|ol|header)\\b[^>]*>")
        val TAG = Regex("(?s)<[^>]+>")
        val INLINE_WS = Regex("[ \\t\\x0B\\u000C\\r]+")
        val LINE_LEADING_WS = Regex("\\n[ \\t]+")
        val BLANK_LINES = Regex("\\n{3,}")
        val NUMERIC_ENTITY = Regex("&#(\\d+);")
    }
}
