package com.cyrene.hsr

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * In-memory view of the `hsr_character` table (names en/pt/es + fribbels build metadata).
 * Command paths only ever read the volatile map — no network, no DB round-trip.
 *
 * Freshness is handled entirely off the command path: a scheduled check compares the
 * table's `data_exportado` against [STALE_DAYS] and re-harvests on the scheduler thread
 * when the data ages out (game patches add characters roughly every 6 weeks, so 30 days
 * catches every patch while touching GitHub ~once a month). A failed or implausibly small
 * harvest keeps the previous rows — same keep-last-good contract as [ScoreWeights].
 */
@Component
class HsrCharacterService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val harvester: FribbelsHarvester,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var table: Map<String, HsrCharacter> = emptyMap()

    fun byId(id: String): HsrCharacter? = loaded()[id]

    /**
     * Game id → PT display name, for callers that render character names to users
     * (e.g. the knowledge ingest localizing team lists). Ids without a PT name are
     * absent so callers keep their own fallback; empty when the table isn't loaded.
     */
    fun ptNames(): Map<String, String> = buildMap {
        loaded().forEach { (id, c) -> c.namePt?.let { put(id, it) } }
    }

    fun fribbelsMeta(id: String): FribbelsMeta? = loaded()[id]?.fribbels

    /**
     * Resolves a user-typed name in any of the three languages to a character id:
     * exact normalized match first, then a containment match that lands on a single
     * character (two candidates = ambiguous = null, mirroring the showcase matcher).
     */
    fun resolveId(query: String): String? = resolve(query, loaded().values)

    /**
     * Gazetteer lookup: every character whose name (any language) appears as a whole word
     * in the free-form [text]. Used by the AI paths — intent fast-path, roster guard and
     * query enrichment — so a message like "quem é a acheron?" is recognized as HSR without
     * an LLM call. Reads only the in-memory table; an empty/unloaded table just returns [].
     */
    fun findInText(text: String): List<HsrCharacter> = charactersIn(text, loaded().values)

    /** Every 6h: reload from DB if never loaded, then re-harvest when the data ages out. */
    @Scheduled(initialDelay = 1, fixedDelay = 6 * 60, timeUnit = TimeUnit.MINUTES)
    fun refreshIfStale() {
        try {
            val newest = jdbc.queryForObject(
                "SELECT max(data_exportado) FROM hsr_character",
                Timestamp::class.java,
            )
            if (newest != null && newest.toInstant().isAfter(Instant.now().minus(Duration.ofDays(STALE_DAYS)))) {
                if (table.isEmpty()) loadFromDb()
                return
            }
            log.info("hsr_character: dados com mais de {} dias (ou vazios) — recolhendo", STALE_DAYS)
            harvestAndStore()
        } catch (e: Exception) {
            log.warn("hsr_character: refresh falhou — mantendo {} personagens em memória: {}", table.size, e.message)
        }
    }

    private fun harvestAndStore() {
        val chars = harvester.harvest()
        // Sanity floor: the game has 90+ characters; fewer than 50 parsed means the source
        // moved/broke, not that characters vanished — keep what we have.
        if (chars.size < 50 || chars.count { it.fribbels != null } < 50) {
            log.warn(
                "hsr_character: colheita implausível ({} personagens, {} com metadados) — mantendo dados anteriores",
                chars.size, chars.count { it.fribbels != null },
            )
            return
        }
        chars.forEach { c ->
            jdbc.update(
                """
                INSERT INTO hsr_character (id, name_en, name_pt, name_es, fribbels, data_exportado)
                VALUES (?, ?, ?, ?, ?::jsonb, now())
                ON CONFLICT (id) DO UPDATE SET
                    name_en = EXCLUDED.name_en,
                    name_pt = EXCLUDED.name_pt,
                    name_es = EXCLUDED.name_es,
                    fribbels = EXCLUDED.fribbels,
                    data_exportado = EXCLUDED.data_exportado
                """.trimIndent(),
                c.id, c.nameEn, c.namePt, c.nameEs, c.fribbels?.toJson(mapper),
            )
        }
        loadFromDb()
        log.info("hsr_character: colheita ok — {} personagens salvos", chars.size)
    }

    @Synchronized
    private fun loadFromDb() {
        table = jdbc.query(
            "SELECT id, name_en, name_pt, name_es, fribbels::text AS fribbels FROM hsr_character",
        ) { rs, _ ->
            HsrCharacter(
                id = rs.getString("id"),
                nameEn = rs.getString("name_en"),
                namePt = rs.getString("name_pt"),
                nameEs = rs.getString("name_es"),
                fribbels = rs.getString("fribbels")?.let { FribbelsMeta.fromJson(mapper.readTree(it)) },
            )
        }.associateBy { it.id }
    }

    /** DB-only lazy load for calls that beat the first scheduled tick; never touches the network. */
    private fun loaded(): Map<String, HsrCharacter> {
        if (table.isEmpty()) {
            try {
                loadFromDb()
            } catch (e: Exception) {
                log.warn("hsr_character: leitura do banco falhou: {}", e.message)
            }
        }
        return table
    }

    companion object {
        const val STALE_DAYS = 30L

        private val DIACRITICS = Regex("\\p{M}+")
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Accent/case/punctuation-insensitive form so "marco" finds "Março 7" and a user typing
         * "dan heng embebidor lunae" finds "Dan Heng - Embebidor Lunae" — multi-form ("SP")
         * names are stored with "•"/"-"/":" separators nobody types, so punctuation folds to
         * a single space instead of surviving into the comparison.
         */
        fun normalize(s: String): String =
            NON_ALNUM.replace(
                DIACRITICS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD), ""),
                " ",
            ).trim()

        /**
         * Pure matching core of [resolveId]: exact normalized match on any language first,
         * then a containment match that lands on a single character id — two candidate ids
         * is ambiguous and returns null, mirroring the showcase matcher's contract.
         */
        internal fun resolve(query: String, chars: Collection<HsrCharacter>): String? {
            val q = normalize(query)
            if (q.isEmpty()) return null
            chars.firstOrNull { c -> c.names.any { normalize(it) == q } }?.let { return it.id }
            return chars.filter { c -> c.names.any { normalize(it).contains(q) || q.contains(normalize(it)) } }
                .map { it.id }
                .distinct()
                .singleOrNull()
        }

        /**
         * Names that collide with everyday Portuguese words — matching them in free chat
         * would flag half the server as HSR questions ("passou pela loja", "jogar domingo").
         * They still resolve normally via [resolve], where the input IS a name (/build).
         */
        // ponytail: hand-kept stoplist; extend when a collision shows up in the logs.
        private val NAME_STOPLIST = setOf("pela", "domingo")

        /**
         * Pure core of [findInText]: characters with at least one name (≥4 normalized chars,
         * not stoplisted) present in [text] as a whole word, longest name winning its span —
         * "dan heng embebidor lunae" fires ONLY the Embebidor form, not base Dan Heng too.
         * Whole-word matters for the same reason as the roster guard's: "cora" must not fire
         * inside "coração".
         */
        internal fun charactersIn(text: String, chars: Collection<HsrCharacter>): List<HsrCharacter> {
            val eligible = chars.flatMap { c -> c.names.filter { normalize(it) !in NAME_STOPLIST } }
            val matched = matchLongest(text, eligible).toSet()
            return chars.filter { c -> c.names.any { it in matched } }
        }

        /**
         * Whole-word matches of [names] (≥4 normalized chars) in [text], longest name first,
         * each match claiming every span it occupies: a name that only occurs INSIDE an
         * already-claimed span is not a match. This is what keeps a base character from
         * co-firing on its own SP form — "Robin" is a sub-phrase of "Robin • Summeretto" —
         * while "compara a robin com a robin summeretto" still fires both (the bare "robin"
         * sits outside the claimed span). Names normalizing identically all match together
         * (the KB stores "Himeko - Nova" and "Himeko • Nova" from different sources).
         * Returns raw names, most specific (longest) first. Pure.
         */
        internal fun matchLongest(text: String, names: Collection<String>): List<String> {
            val t = normalize(text)
            if (t.isEmpty()) return emptyList()
            val byNorm = names.distinct().groupBy(::normalize).filterKeys { it.length >= 4 }
            val claimed = mutableListOf<IntRange>()
            val matched = mutableListOf<String>()
            for ((norm, raws) in byNorm.entries.sortedByDescending { it.key.length }.map { it.toPair() }) {
                val spans = wordSpans(t, norm).filter { s -> claimed.none { it.first <= s.last && s.first <= it.last } }
                if (spans.isEmpty()) continue
                claimed += spans
                matched += raws
            }
            return matched
        }

        /** Every whole-word span of [word] in already-normalized [text]. */
        private fun wordSpans(text: String, word: String): List<IntRange> = buildList {
            var i = text.indexOf(word)
            while (i >= 0) {
                val end = i + word.length
                val beforeOk = i == 0 || !text[i - 1].isLetterOrDigit()
                val afterOk = end >= text.length || !text[end].isLetterOrDigit()
                if (beforeOk && afterOk) add(i until end)
                i = text.indexOf(word, i + 1)
            }
        }

        /** Whole-word containment over already-normalized text, without per-name regexes. */
        internal fun containsWord(text: String, word: String): Boolean {
            if (word.isEmpty()) return false
            var i = text.indexOf(word)
            while (i >= 0) {
                val beforeOk = i == 0 || !text[i - 1].isLetterOrDigit()
                val afterOk = i + word.length >= text.length || !text[i + word.length].isLetterOrDigit()
                if (beforeOk && afterOk) return true
                i = text.indexOf(word, i + 1)
            }
            return false
        }
    }
}
