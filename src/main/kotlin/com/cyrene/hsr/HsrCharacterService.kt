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
 * Multilingual character gazetteer (game id ↔ pt/en names), read from `personagem_hsr` — the rich
 * V17 table the [SrsNanokaHarvester] populates. Command paths only ever read the volatile map — no
 * network, no DB round-trip. Also owns the fribbels build metadata (`hsr_build_meta`) that backs
 * `/build`'s scoring fallback.
 *
 * The gazetteer is never harvested here: `personagem_hsr` is refreshed by the SrsNanoka harvest,
 * and this just reloads it from the DB each scheduled tick. Only the fribbels metadata has its own
 * staleness check ([STALE_DAYS]); a failed/implausible harvest keeps the previous rows — same
 * keep-last-good contract as [ScoreWeights].
 */
@Component
class HsrCharacterService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val buildMetaHarvester: FribbelsHarvester,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var table: Map<String, HsrCharacter> = emptyMap()

    /** id → unambiguous display name, recomputed with [table]; see [displayNames]. */
    @Volatile
    private var displayNames: Map<String, String> = emptyMap()

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

    /**
     * The name to SHOW for a character — bare ("Herta") unless another row shares it, in which
     * case the Path disambiguates it ("7 de Março (A Caça)"). Every render path goes through
     * here so a variant is never displayed under a label that could mean two characters, and so
     * the vector store's `name` metadata stays one-entity-per-key. Falls back to the id when the
     * gazetteer isn't loaded, matching the rest of this class's fail-open contract.
     */
    fun displayName(id: String): String {
        if (displayNames.isEmpty()) loaded()
        return displayNames[id] ?: table[id]?.baseName ?: id
    }

    /**
     * Every 6h: reload the gazetteer + build meta from the DB (cheap; picks up whatever the
     * SrsNanoka harvest wrote to `personagem_hsr`), then re-harvest the fribbels build meta when
     * it ages out past [STALE_DAYS].
     */
    @Scheduled(initialDelay = 1, fixedDelay = 6 * 60, timeUnit = TimeUnit.MINUTES)
    fun refreshIfStale() {
        try {
            loadFromDb()
            val newest = jdbc.queryForObject(
                "SELECT max(data_exportado) FROM hsr_build_meta",
                Timestamp::class.java,
            )
            if (newest != null && newest.toInstant().isAfter(Instant.now().minus(Duration.ofDays(STALE_DAYS)))) return
            log.info("hsr_build_meta: dados com mais de {} dias (ou vazios) — recolhendo", STALE_DAYS)
            storeBuildMeta(buildMetaHarvester.harvest())
            loadFromDb()
        } catch (e: Exception) {
            log.warn("gazetteer/build-meta: refresh falhou — mantendo {} personagens em memória: {}", table.size, e.message)
        }
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
            "SELECT character_id, nome, nome_en, caminho, elemento, raridade, faccao " +
                "FROM personagem_hsr WHERE character_id IS NOT NULL",
        ) { rs, _ ->
            HsrCharacter(
                id = rs.getInt("character_id").toString(),
                namePt = rs.getString("nome"),
                nameEn = rs.getString("nome_en"),
                caminho = rs.getString("caminho"),
                elemento = rs.getString("elemento"),
                raridade = rs.getInt("raridade").takeUnless { rs.wasNull() },
                faccao = rs.getString("faccao"),
            )
        }.associateBy { it.id }
        displayNames = displayNames(table.values)
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
         * The same collision one level down: single WORDS of a multi-word name that are also
         * everyday Portuguese. [fuzzyMatch]'s partial-name tier resolves a name from any one of
         * its distinctive words, which turned "alguém quer jogar hoje à noite" into Noite Eterna
         * and "terminei a missão nova" into Himeko • Nova. Found by running the matcher over the
         * real 97-row roster against ordinary chat lines, not from a log — see the phase-7 notes.
         *
         * "marco" is deliberately NOT here: it's the whole point of the tier ("a março de caça"),
         * and the full name is still what an exact match needs.
         */
        private val TOKEN_STOPLIST = setOf("noite", "eterna", "nova", "cisne", "negro", "vaga", "lume")

        /**
         * Pure core of [findInText]: characters with at least one name (≥4 normalized chars,
         * not stoplisted) present in [text] as a whole word, longest name winning its span —
         * "dan heng embebidor lunae" fires ONLY the Embebidor form, not base Dan Heng too.
         * Whole-word matters for the same reason as the roster guard's: "cora" must not fire
         * inside "coração".
         */
        internal fun charactersIn(text: String, chars: Collection<HsrCharacter>): List<HsrCharacter> {
            val eligible = chars.flatMap { c -> c.names.filter { normalize(it) !in NAME_STOPLIST } }
            // Exact whole-word first; only a total miss falls through to [fuzzyMatch], so an
            // exact hit is never widened by it.
            val matched = matchLongest(text, eligible)
                .ifEmpty { fuzzyMatch(text, eligible) }
                .toSet()
            return disambiguate(text, chars.filter { c -> c.names.any { it in matched } })
        }

        /**
         * Narrows a name match that landed on SEVERAL characters using the Path/Element named in
         * the same text: "kit da março de caça" hits both 7 de Março rows by name, and only the
         * Hunt one survives here. A name is ambiguous for 14 rows (both Márcios, five Desbravador
         * + five Desbravadora paths) and `nome_en` "Trailblazer" is shared by all ten, so without
         * this every question about them rendered two-to-ten stacked kits.
         *
         * Same only-ever-raises-precision contract as the retrieval filters: no Path/Element in
         * the text, or a filter that would empty a group, leaves that group untouched. Groups are
         * keyed by normalized base name so an unambiguous character in a multi-hit query
         * ("compara a herta com a march de caça") is never touched by the other's filter. Pure.
         */
        internal fun disambiguate(text: String, hits: List<HsrCharacter>): List<HsrCharacter> {
            if (hits.size < 2) return hits
            val path = HsrTaxonomy.pathIn(text)
            val element = HsrTaxonomy.elementIn(text)
            if (path == null && element == null) return hits
            return hits.groupBy { normalize(it.baseName) }.values.flatMap { group ->
                if (group.size < 2) group
                else group.filter { c ->
                    (path == null || HsrTaxonomy.canonicalPath(c.caminho) == path) &&
                        (element == null || HsrTaxonomy.canonicalElement(c.elemento) == element)
                }.ifEmpty { group }
            }
        }

        /**
         * id → display name: the bare name, suffixed with the canonical Path only for the rows
         * whose name another row also carries. Computed over the WHOLE roster (ambiguity is a
         * property of the set, not of a row), so it lives here rather than on [HsrCharacter].
         * Pure.
         */
        internal fun displayNames(chars: Collection<HsrCharacter>): Map<String, String> {
            val shared = chars.groupingBy { normalize(it.baseName) }.eachCount()
            return chars.associate { c ->
                val path = HsrTaxonomy.canonicalPath(c.caminho)
                c.id to when {
                    (shared[normalize(c.baseName)] ?: 0) > 1 && path != null -> "${c.baseName} ($path)"
                    else -> c.baseName
                }
            }
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
         * Name resolution for users who don't type the stored name exactly — "a março de caça"
         * for "7 de Março", "embebidor" for "Dan Heng - Embebidor Lunae", "acheronn" for
         * "Acheron". Runs ONLY as a fallback after [matchLongest] found nothing, so it can convert
         * a miss into a hit but can never loosen a match that already worked.
         *
         * Two tiers, tightest first:
         *
         *  1. **Distinctive token** — a word of ≥4 chars that belongs to exactly ONE name in the
         *     pool. "marco" occurs only in "7 de Março", so it resolves with no scoring at all;
         *     "herta" occurs in both "Herta" and "A Herta", so it is NOT distinctive and is left
         *     alone rather than guessed at. This is what handles partial names, which is the
         *     common case, and it is exact — no threshold to tune, no false positives possible.
         *  2. **Trigram containment** — the fraction of a query window's character trigrams
         *     present in the name, for typos. Needs [MIN_TRIGRAM] and must beat the runner-up by
         *     [TRIGRAM_MARGIN], so an ambiguous near-miss resolves to nothing instead of to the
         *     wrong character. A miss here is harmless: the caller falls through to retrieval.
         *
         * Done in Kotlin over the in-memory pool rather than with pg_trgm because the pool is ~200
         * short strings (a GIN index buys nothing at that size) and because trigram matching in
         * Postgres is byte-based — "marco" does not match "Março" there without also installing
         * `unaccent`, while [normalize] has already folded accents here. Pure.
         */
        // ponytail: O(names × query windows) ≈ 10k tiny set ops, only on the exact-match miss path.
        internal fun fuzzyMatch(text: String, names: Collection<String>): List<String> {
            val t = normalize(text)
            if (t.isEmpty()) return emptyList()
            val byNorm = names.distinct().groupBy(::normalize).filterKeys { it.length >= 4 }
            if (byNorm.isEmpty()) return emptyList()

            // Tier 1: tokens owned by exactly one name. Restricted to MULTI-WORD names because a
            // single-word name is its own token — [matchLongest] would already have matched it,
            // so this tier could only ever fire for it on text where the exact tier said no.
            val owners = mutableMapOf<String, MutableSet<String>>()
            for (norm in byNorm.keys) {
                val toks = norm.split(' ')
                if (toks.size < 2) continue
                for (tok in toks) {
                    if (tok.length >= 4 && tok !in TOKEN_STOPLIST) owners.getOrPut(tok) { mutableSetOf() } += norm
                }
            }
            val distinctive = owners.entries
                .filter { (tok, holders) -> holders.size == 1 && containsWord(t, tok) }
                .flatMap { it.value }
                .distinct()
            if (distinctive.isNotEmpty()) return distinctive.flatMap { byNorm.getValue(it) }

            // Tier 1b: the name written without its separators — "vagalume" for "Vaga-lume",
            // "danheng" for "Dan Heng". This is an EXACT string match on a space-stripped form, so
            // it carries none of tier 2's risk, and it's the spacing variant users type most.
            val squashed = byNorm.entries
                .filter { (norm, _) -> norm.contains(' ') && containsWord(t, norm.replace(" ", "")) }
                .flatMap { it.value }
            if (squashed.isNotEmpty()) return squashed

            // Tier 2: trigram similarity over every contiguous query window.
            val words = t.split(' ').filter { it.isNotEmpty() }
            val maxWords = byNorm.keys.maxOf { it.count { c -> c == ' ' } + 1 }
            val windows = buildList {
                for (n in 1..maxWords) {
                    for (i in 0..words.size - n) {
                        val w = words.subList(i, i + n).joinToString(" ")
                        if (w.length >= 4) add(w)
                    }
                }
            }
            if (windows.isEmpty()) return emptyList()
            val scored = byNorm.map { (norm, raws) ->
                val nameTri = trigrams(norm)
                Triple(norm, raws, windows.maxOf { dice(trigrams(it), nameTri) })
            }.filter { it.third >= MIN_TRIGRAM }.sortedByDescending { it.third }

            val top = scored.firstOrNull() ?: return emptyList()
            val runnerUp = scored.drop(1).firstOrNull { it.first != top.first }
            if (runnerUp != null && top.third - runnerUp.third < TRIGRAM_MARGIN) return emptyList()
            return top.second
        }

        /**
         * Score a typo must reach. Set deliberately HIGH: "acheronte" (the mythological river,
         * a different word) scores 0.75 against "Acheron" while the typo "acheronn" scores 0.80,
         * and no threshold separates 0.75 from legitimate near-misses like "hyacinth"→Hyacine
         * (0.67). Faced with that overlap this tier keeps the codebase's existing preference for
         * precision — the same one the roster guard and the whole-word matcher encode — so a
         * marginal typo falls through to retrieval instead of resolving to a plausible wrong name.
         */
        private const val MIN_TRIGRAM = 0.78

        /** How far the best name must beat the next one — otherwise it's a guess, so we don't. */
        private const val TRIGRAM_MARGIN = 0.15

        /**
         * True when [token] is within typo distance of [word] — the same Dice bar [fuzzyMatch]'s
         * scored tier uses, exposed so the closed vocabularies ([HsrTaxonomy]) tolerate the same
         * typos names do. Live miss it fixes: "recordaçãp" resolved no Path, so "build do
         * desbravador da recordaçãp" fell back to all five Trailblazers and rendered the wrong one.
         */
        internal fun nearWord(token: String, word: String): Boolean =
            dice(trigrams(token), trigrams(word)) >= MIN_TRIGRAM

        /** Space-padded character trigrams, so word boundaries count (as in pg_trgm). */
        private fun trigrams(s: String): Set<String> {
            val padded = " $s "
            if (padded.length < 3) return emptySet()
            return (0..padded.length - 3).mapTo(mutableSetOf()) { padded.substring(it, it + 3) }
        }

        /**
         * Sørensen–Dice over trigram sets — SYMMETRIC on purpose. A one-sided containment score
         * ("how much of the query is inside the name") is the right measure for a partial name,
         * which is exactly what tier 1 already handles under a stoplist; reusing it here let a
         * short everyday word score a perfect 1.0 against a long name it happens to sit inside
         * ("noite" ⊂ "Noite Eterna") and silently bypass that stoplist. A typo barely changes
         * length, so penalizing the length gap costs nothing here and closes that hole.
         */
        private fun dice(a: Set<String>, b: Set<String>): Double =
            if (a.isEmpty() || b.isEmpty()) 0.0
            else 2.0 * a.count { it in b } / (a.size + b.size)

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
