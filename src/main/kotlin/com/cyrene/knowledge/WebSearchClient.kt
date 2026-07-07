package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.cyrene.ai.AiMetrics
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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
    private val metrics: AiMetrics,
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
     *
     * [recent] marks a news-shaped query (announcements, leaks, "novo X"): SearXNG gets
     * `time_range=month` so fresh pages outrank stale SEO guides, and one extra page is
     * fetched with a proportionally smaller per-page cap — the total char budget (and thus
     * the num_ctx sizing) stays the same, it's just spread across more sources.
     */
    fun search(query: String, limit: Int = 5, recent: Boolean = false): List<WebSearchResult> {
        val root = baseUrl ?: return emptyList()
        val scoped = "Honkai Star Rail $query"
        val timeRange = if (recent) "&time_range=month" else ""
        val uri = URI.create(
            "$root/search?q=${URLEncoder.encode(scoped, StandardCharsets.UTF_8)}&format=json&safesearch=0$timeRange",
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
            // News queries spread the same total char budget across one more page, so a
            // third source (often the actual announcement) gets read without growing the
            // prompt the voice/verify passes must fit in num_ctx.
            val basePages = properties.knowledge.webFetchPages
            val pages = if (recent) basePages + 1 else basePages
            val charLimit = properties.knowledge.webFetchCharLimit * basePages / pages
            enrichWithPageText(parse(response.body(), limit), pages, charLimit)
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
     * between the model seeing a one-line snippet and seeing the actual kit table.
     *
     * The fetches run CONCURRENTLY (via [HttpClient.sendAsync]), so total latency is roughly
     * one page fetch instead of the sum — with the default 2 pages at an 8s request timeout
     * each, worst case drops from ~16s to ~8s. Each future is still bounded by its own request
     * timeout, and we add a small overall [FETCH_AWAIT_SECONDS] join guard so a single hung
     * connection can't stall the whole knowledge turn. Any page that times out, errors, or
     * isn't HTML is simply left snippet-only.
     */
    private fun enrichWithPageText(results: List<WebSearchResult>, pages: Int, charLimit: Int): List<WebSearchResult> {
        if (pages <= 0 || results.isEmpty()) return results

        return metrics.timePass("web_fetch") {
            // Kick off all fetches up front so they overlap, then collect their results in order.
            val inFlight = results.take(pages).map { fetchReadableTextAsync(it.url, charLimit) }

            results.mapIndexed { i, r ->
                if (i >= pages) return@mapIndexed r
                val text = try {
                    inFlight[i].get(FETCH_AWAIT_SECONDS, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    log.debug("Page fetch join failed for {}: {}", r.url, e.message)
                    ""
                }
                if (text.isNotBlank()) r.copy(content = text) else r
            }
        }
    }

    /**
     * Asynchronously downloads [url] and reduces it to readable text (tags stripped), capped
     * at [charLimit]. Returns a never-failing future: any error completes it with "" so the
     * caller treats the page as snippet-only. A malformed URL can throw synchronously while
     * building the request, hence the outer try.
     */
    private fun fetchReadableTextAsync(url: String, charLimit: Int): CompletableFuture<String> {
        return try {
            val request = HttpRequest.newBuilder(URI.create(fetchableUrl(url)))
                .timeout(Duration.ofSeconds(8))
                // A real-browser UA: many fan wikis 403 the default Java UA.
                .header("User-Agent", "Mozilla/5.0 (compatible; CyreneBot/1.0; +discord)")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build()
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply { response -> extractReadableText(response, url, charLimit) }
                .exceptionally { e ->
                    log.debug("Page fetch failed for {}: {}", url, e.message)
                    ""
                }
        } catch (e: Exception) {
            log.debug("Page fetch setup failed for {}: {}", url, e.message)
            CompletableFuture.completedFuture("")
        }
    }

    /** Validates an [HttpResponse] is OK + HTML, then reduces its body to capped readable text. */
    private fun extractReadableText(response: HttpResponse<String>, url: String, charLimit: Int): String {
        if (response.statusCode() !in 200..299) {
            log.debug("Page fetch HTTP {} for {}", response.statusCode(), url)
            return ""
        }
        val contentType = response.headers().firstValue("content-type").orElse("")
        if (contentType.isNotEmpty() && !contentType.contains("html", ignoreCase = true)) return ""
        val body = response.body().let { if (it.length > MAX_HTML_CHARS) it.substring(0, MAX_HTML_CHARS) else it }
        return htmlToText(body).take(charLimit)
    }

    internal companion object {
        /** Hard cap on raw HTML processed, so a pathological page can't make the regexes crawl. */
        const val MAX_HTML_CHARS = 500_000

        /**
         * Overall join budget per page fetch. Slightly above the 8s request timeout so a
         * normal slow page completes on its own, while a connection that hangs past the
         * request timeout still can't stall the knowledge turn indefinitely.
         */
        const val FETCH_AWAIT_SECONDS = 10L

        //   = non-breaking space: jsoup decodes &nbsp; to it, and the model reads it
        // better as an ordinary space, so fold it in with the other inline whitespace.
        val INLINE_WS = Regex("[ \\t\\x0B\\u000C\\r\\u00A0]+")
        val LINE_LEADING_WS = Regex("\\n[ \\t]+")
        val BLANK_LINES = Regex("\\n{3,}")

        /** Non-content elements dropped before text extraction. */
        const val STRIP_SELECTOR = "script, style, noscript, svg, head, nav, footer, form, template"

        private val REDDIT_HOST = Regex("^(https?://)(www\\.)?reddit\\.com/", RegexOption.IGNORE_CASE)

        /**
         * Rewrites the URL to a variant our plain-HTTP fetcher can actually read. Today that's
         * one case: new reddit (`www.reddit.com`) serves a JS shell with no post content, while
         * `old.reddit.com` is server-rendered HTML — and leak/announcement threads rank high
         * for exactly the news queries we care about. Pure.
         */
        internal fun fetchableUrl(url: String): String =
            REDDIT_HOST.replace(url) { m -> "${m.groupValues[1]}old.reddit.com/" }

        /** Tags whose close should produce a line break, so kit sections don't run together. */
        val BLOCK_TAGS = setOf(
            "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
            "section", "article", "table", "ul", "ol", "header",
        )

        /**
         * Reduces an HTML page to readable plain text via a real jsoup DOM parse — robust
         * against malformed markup, comments, CDATA and nested tags in a way the previous
         * hand-rolled regex never was, and it decodes every HTML entity correctly. Non-content
         * elements are removed, block-level closes become line breaks (so a multi-ability kit
         * keeps its structure), and whitespace is tidied. Companion-scoped + pure, so it stays
         * unit-testable.
         */
        internal fun htmlToText(html: String): String {
            val doc = Jsoup.parse(html)
            doc.select(STRIP_SELECTOR).remove()

            val sb = StringBuilder()
            val body = doc.body()
            NodeTraversor.traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    if (node is TextNode) sb.append(node.wholeText)
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.normalName() in BLOCK_TAGS) sb.append('\n')
                }
            }, body)

            var s = sb.toString()
            s = s.replace(INLINE_WS, " ")
            s = s.replace(LINE_LEADING_WS, "\n")
            s = s.replace(BLANK_LINES, "\n\n")
            return s.trim()
        }

        /** Decodes HTML entities (named + numeric) using jsoup's parser. */
        internal fun decodeEntities(s: String): String = Parser.unescapeEntities(s, false)
    }
}
