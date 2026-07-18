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
    private val kitHarvester: HsrCharacterHarvester,
    private val buildMetaHarvester: FribbelsHarvester,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var table: Map<String, HsrCharacter> = emptyMap()

    /** Fribbels build metadata (id → weights/sets), split into its own `hsr_build_meta` table. */
    @Volatile
    private var buildMeta: Map<String, FribbelsMeta> = emptyMap()

    fun byId(id: String): HsrCharacter? = loaded()[id]

    /**
     * Snapshot of every loaded character. Used as the multilingual name pool for KB name-
     * anchoring: the vector store stores PT names only, so an EN/ES query ("the herta") can't
     * anchor against the KB alone — the gazetteer bridges it. Empty when the table isn't loaded.
     */
    fun all(): Collection<HsrCharacter> = loaded().values

    /**
     * Game id → PT display name, for callers that render character names to users
     * (e.g. the knowledge ingest localizing team lists). Ids without a PT name are
     * absent so callers keep their own fallback; empty when the table isn't loaded.
     */
    fun ptNames(): Map<String, String> = buildMap {
        loaded().forEach { (id, c) -> c.namePt?.let { put(id, it) } }
    }

    fun fribbelsMeta(id: String): FribbelsMeta? {
        if (table.isEmpty()) loaded() // ensures buildMeta is loaded alongside the table
        return buildMeta[id]
    }

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
        storeKit(kitHarvester.harvest())
        storeBuildMeta(buildMetaHarvester.harvest())
        loadFromDb()
    }

    /** Upserts the full-kit rows. Sanity floor: <50 chars = the source moved/broke, keep what we have. */
    private fun storeKit(chars: List<HsrCharacter>) {
        if (chars.size < 50) {
            log.warn("hsr_character: colheita implausível ({} personagens) — mantendo dados anteriores", chars.size)
            return
        }
        chars.forEach { c ->
            jdbc.update(
                """
                INSERT INTO hsr_character (
                    id, nome, nome_en, elemento, raridade, caminho, faccao, descricao,
                    atq_basico, pericia, pericia_suprema, talento, tecnica,
                    traco_a2, traco_a4, traco_a6,
                    eidolon1, eidolon2, eidolon3, eidolon4, eidolon5, eidolon6,
                    detalhes_personagem, historia_personagem_parte1, historia_personagem_parte2,
                    historia_personagem_parte3, historia_personagem_parte4, data_exportado
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET
                    nome = EXCLUDED.nome, nome_en = EXCLUDED.nome_en, elemento = EXCLUDED.elemento,
                    raridade = EXCLUDED.raridade, caminho = EXCLUDED.caminho, faccao = EXCLUDED.faccao,
                    descricao = EXCLUDED.descricao, atq_basico = EXCLUDED.atq_basico,
                    pericia = EXCLUDED.pericia, pericia_suprema = EXCLUDED.pericia_suprema,
                    talento = EXCLUDED.talento, tecnica = EXCLUDED.tecnica,
                    traco_a2 = EXCLUDED.traco_a2, traco_a4 = EXCLUDED.traco_a4, traco_a6 = EXCLUDED.traco_a6,
                    eidolon1 = EXCLUDED.eidolon1, eidolon2 = EXCLUDED.eidolon2, eidolon3 = EXCLUDED.eidolon3,
                    eidolon4 = EXCLUDED.eidolon4, eidolon5 = EXCLUDED.eidolon5, eidolon6 = EXCLUDED.eidolon6,
                    detalhes_personagem = EXCLUDED.detalhes_personagem,
                    historia_personagem_parte1 = EXCLUDED.historia_personagem_parte1,
                    historia_personagem_parte2 = EXCLUDED.historia_personagem_parte2,
                    historia_personagem_parte3 = EXCLUDED.historia_personagem_parte3,
                    historia_personagem_parte4 = EXCLUDED.historia_personagem_parte4,
                    data_exportado = EXCLUDED.data_exportado
                """.trimIndent(),
                c.id, c.namePt, c.nameEn, c.elemento, c.raridade, c.caminho, c.faccao, c.descricao,
                c.atqBasico, c.pericia, c.periciaSuprema, c.talento, c.tecnica,
                c.tracoA2, c.tracoA4, c.tracoA6,
                c.eidolon1, c.eidolon2, c.eidolon3, c.eidolon4, c.eidolon5, c.eidolon6,
                c.detalhesPersonagem, c.historiaParte1, c.historiaParte2, c.historiaParte3, c.historiaParte4,
            )
        }
        log.info("hsr_character: colheita ok — {} personagens salvos", chars.size)
    }

    /** Upserts the fribbels build metadata. <50 scored = a repo-side format break, keep what we have. */
    private fun storeBuildMeta(meta: Map<String, FribbelsMeta>) {
        if (meta.size < 50) {
            log.warn("hsr_build_meta: colheita implausível ({} metadados) — mantendo dados anteriores", meta.size)
            return
        }
        meta.forEach { (id, m) ->
            jdbc.update(
                """
                INSERT INTO hsr_build_meta (id, fribbels, data_exportado) VALUES (?, ?::jsonb, now())
                ON CONFLICT (id) DO UPDATE SET fribbels = EXCLUDED.fribbels, data_exportado = EXCLUDED.data_exportado
                """.trimIndent(),
                id, m.toJson(mapper),
            )
        }
        log.info("hsr_build_meta: colheita ok — {} metadados salvos", meta.size)
    }

    @Synchronized
    private fun loadFromDb() {
        table = jdbc.query(
            """
            SELECT id, nome, nome_en, elemento, raridade, caminho, faccao, descricao,
                   atq_basico, pericia, pericia_suprema, talento, tecnica,
                   traco_a2, traco_a4, traco_a6,
                   eidolon1, eidolon2, eidolon3, eidolon4, eidolon5, eidolon6,
                   detalhes_personagem, historia_personagem_parte1, historia_personagem_parte2,
                   historia_personagem_parte3, historia_personagem_parte4
            FROM hsr_character
            """.trimIndent(),
        ) { rs, _ ->
            HsrCharacter(
                id = rs.getString("id"),
                namePt = rs.getString("nome"),
                nameEn = rs.getString("nome_en"),
                elemento = rs.getString("elemento"),
                raridade = rs.getInt("raridade").takeUnless { rs.wasNull() },
                caminho = rs.getString("caminho"),
                faccao = rs.getString("faccao"),
                descricao = rs.getString("descricao"),
                atqBasico = rs.getString("atq_basico"),
                pericia = rs.getString("pericia"),
                periciaSuprema = rs.getString("pericia_suprema"),
                talento = rs.getString("talento"),
                tecnica = rs.getString("tecnica"),
                tracoA2 = rs.getString("traco_a2"),
                tracoA4 = rs.getString("traco_a4"),
                tracoA6 = rs.getString("traco_a6"),
                eidolon1 = rs.getString("eidolon1"),
                eidolon2 = rs.getString("eidolon2"),
                eidolon3 = rs.getString("eidolon3"),
                eidolon4 = rs.getString("eidolon4"),
                eidolon5 = rs.getString("eidolon5"),
                eidolon6 = rs.getString("eidolon6"),
                detalhesPersonagem = rs.getString("detalhes_personagem"),
                historiaParte1 = rs.getString("historia_personagem_parte1"),
                historiaParte2 = rs.getString("historia_personagem_parte2"),
                historiaParte3 = rs.getString("historia_personagem_parte3"),
                historiaParte4 = rs.getString("historia_personagem_parte4"),
            )
        }.associateBy { it.id }
        buildMeta = jdbc.query("SELECT id, fribbels::text AS fribbels FROM hsr_build_meta") { rs, _ ->
            rs.getString("id") to FribbelsMeta.fromJson(mapper.readTree(rs.getString("fribbels")))
        }.toMap()
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
         * Player shorthands that NO data source lists as a name → the canonical variant name
         * the KB/gazetteer actually carry. "dan heng il"/"dan heng pt" are the Imbibitor Lunae
         * / Permansor Terrae forms; left alone they whole-word-match only base "Dan Heng", so
         * every build/kit question about them answered the base character. Extend as new
         * shorthands surface (the English "imbibitor/permansor" full names still resolve on
         * their own; only the initialisms need this). Case-insensitive, whole-phrase.
         */
        // ponytail: hand-kept alias map; grows one line per shorthand players actually type.
        private val NICKNAMES: Map<Regex, String> = mapOf(
            Regex("\\bdan heng il\\b", RegexOption.IGNORE_CASE) to "Dan Heng - Embebidor Lunae",
            Regex("\\bdan heng pt\\b", RegexOption.IGNORE_CASE) to "Dan Heng - Permansor Terrae",
        )

        /**
         * Expands the [NICKNAMES] shorthands in [query] to the canonical variant name before
         * any name matching runs; the rest of the query is left verbatim (downstream matchers
         * normalize anyway). Pure, so the alias set is unit-testable without a DB.
         */
        fun expandNicknames(query: String): String {
            var out = query
            for ((re, full) in NICKNAMES) out = re.replace(out, full)
            return out
        }

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

        /**
         * Normalized [text] with every whole-word occurrence of [names] blanked out, longest
         * name first ("Himeko • Nova" is removed as a phrase BEFORE bare "Himeko" could break
         * it apart and orphan "nova"). Used to keep entity names out of keyword scans — the
         * "nova" in "himeko nova" is a character, not a recency cue — while the same word
         * standing alone elsewhere in the text survives. Pure.
         */
        internal fun stripNames(text: String, names: Collection<String>): String {
            var t = normalize(text)
            val norms = names.map(::normalize).filter { it.length >= 4 }.distinct().sortedByDescending { it.length }
            for (n in norms) {
                for (span in wordSpans(t, n)) {
                    t = t.replaceRange(span, " ".repeat(n.length))
                }
            }
            return t
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
