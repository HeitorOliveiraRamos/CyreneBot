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
import java.util.concurrent.ConcurrentHashMap

/** Outcome of a showcase fetch — NotFound (bad UID / hidden profile) reads differently to the user than a transient error. */
sealed interface MihomoResult {
    data class Ok(val profile: MihomoProfile) : MihomoResult
    data object NotFound : MihomoResult
    data object Error : MihomoResult
}

/**
 * Fetches a player's showcased characters from mihomo's parsed API
 * (`{mihomoUrl}/{uid}?lang=pt`). Results are cached for [CACHE_TTL_MS] per UID so repeated
 * `/build` calls (comparing two characters, retrying a typo) don't hammer the public API —
 * showcase data only changes when the player edits it in game anyway.
 */
@Component
class MihomoClient(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val cache = ConcurrentHashMap<String, Pair<Long, MihomoProfile>>()

    fun fetch(uid: String): MihomoResult {
        val now = System.currentTimeMillis()
        cache[uid]?.let { (at, profile) -> if (now - at < CACHE_TTL_MS) return MihomoResult.Ok(profile) }

        val url = "${properties.knowledge.mihomoUrl.trimEnd('/')}/$uid?lang=pt"
        return try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (compatible; CyreneBot/1.0; +discord)")
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            when {
                resp.statusCode() == 404 -> MihomoResult.NotFound
                resp.statusCode() !in 200..299 -> {
                    log.warn("Mihomo: HTTP {} for uid {}", resp.statusCode(), uid)
                    MihomoResult.Error
                }
                else -> {
                    val profile = MihomoParser.parse(mapper.readTree(resp.body()))
                    if (cache.size > CACHE_MAX_ENTRIES) {
                        cache.entries.removeIf { now - it.value.first >= CACHE_TTL_MS }
                    }
                    cache[uid] = now to profile
                    MihomoResult.Ok(profile)
                }
            }
        } catch (e: Exception) {
            log.warn("Mihomo: fetch failed for uid {}: {}", uid, e.message)
            MihomoResult.Error
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 5L * 60 * 1000
        private const val CACHE_MAX_ENTRIES = 500
    }
}
