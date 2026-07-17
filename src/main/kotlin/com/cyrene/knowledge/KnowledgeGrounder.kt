package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacterService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Result of grounding a Honkai: Star Rail question against real data.
 *
 * [found] is the hard gate the rest of the pipeline relies on: when it's false there is NO
 * authoritative source, so the only correct action is to abstain — never to let the model
 * answer from its own (frequently wrong) memory. [context] is the formatted source text the
 * voice pass is allowed to retell, and [source] records which tier produced it.
 */
data class Grounding(
    val found: Boolean,
    val context: String,
    val source: Source,
) {
    enum class Source { LOCAL, WEB, NONE }

    companion object {
        val EMPTY = Grounding(found = false, context = "", source = Source.NONE)
    }
}

/**
 * Grounds an HSR question in real data BEFORE the model ever writes a word.
 *
 * This is the deliberate replacement for "ask the brain pass to please call `lookupHsr`":
 * a small local model can't be trusted to (a) actually invoke the tool nor (b) abstain when
 * it comes back empty — that combination is exactly how "Lilita / caminho Eclipse" gets
 * invented. So the retrieval is no longer the model's decision: we run [GameKnowledgeTools]
 * deterministically here, local-first then web, and hand the voice pass ONLY what a real
 * source returned. If neither tier produces anything, [Grounding.found] is false and the
 * caller abstains.
 *
 * Roster guard ([entitySubject] / [subjectMentioned]): for a bare "quem é X" question, the
 * subject name MUST appear in whatever source we retrieved. A semantic search always returns
 * its nearest neighbour even for a name that doesn't exist, and web search can surface SEO
 * noise — so "found something vaguely related" is not "this character is real". If the
 * queried name is absent from the retrieved text, we treat it as a miss and abstain.
 */
@Component
class KnowledgeGrounder(
    private val tools: GameKnowledgeTools,
    private val characters: HsrCharacterService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [onWebSearch] fires just before the (slow) web tier runs, so callers can surface
     * "searching the web…" progress; the local tier is fast enough to not need one.
     *
     * Tier order is decided by [wantsWeb]: questions that smell like NEWS (explicit
     * "pesquisa na internet", announcements, leaks, "novo X", version numbers) go web-FIRST,
     * because the local KB only knows released content — a name-anchored local hit for
     * "novo Aventurine anunciado" would confidently ground the OLD kit and the web would
     * never run. The other tier always remains as fallback, so a misfired cue costs one web
     * round-trip, never an answer.
     */
    fun ground(query: String, onWebSearch: () -> Unit = {}): Grounding {
        // Gazetteer enrichment: when the query names a known character, append the aliases
        // the query DOESN'T use (en/pt/es) to the retrieval string. Users ask in PT-BR
        // against chunks/pages that may only carry the English name — the extra aliases pull
        // the embedding (and the web search) toward the right character. The roster guard
        // below still runs on the ORIGINAL query, so enrichment can't launder a fake name.
        val retrievalQuery = enrichQuery(query, characters.findInText(query).flatMap { it.names })
        if (retrievalQuery != query && log.isDebugEnabled) {
            log.debug("Gazetteer enriched '{}' → '{}'", query, retrievalQuery)
        }

        // Fandom tier (not built yet — the seam for lore-grade grounding): slot a
        // groundFandom(query) here, first for lore-cue questions, backed by the MediaWiki
        // API at https://honkai-star-rail.fandom.com/api.php —
        //   search: ?action=query&list=search&srsearch=<q>&format=json&srlimit=3
        //   page:   ?action=parse&page=<title>&format=json → parse.text["*"] →
        //           WebSearchClient.htmlToText → cap at webFetchCharLimit
        // Return the same Grounding contract (add Source.FANDOM) and the roster guard,
        // verifier and answer cache all apply to it for free.
        // Entity names are invisible to the news cues: "Himeko Nova" contains the recency
        // word "nova", but a character NAME is exactly what the local KB is authoritative
        // for — without the strip, every question about her routed web-first.
        val entityNames = tools.kbNames() + characters.findInText(query).flatMap { it.names }
        val webFirst = wantsWeb(query, entityNames)
        if (webFirst) {
            log.debug("Web-first grounding for '{}' (news/explicit-search cue)", query)
            groundWeb(query, newsQuery(query, characters.findInText(query).mapNotNull { it.nameEn }), recent = true, onWebSearch)?.let { return it }
        }
        groundLocal(query, retrievalQuery)?.let { return it }
        if (!webFirst) {
            groundWeb(query, retrievalQuery, recent = false, onWebSearch)?.let { return it }
        }
        return Grounding.EMPTY
    }

    /** Local KB tier; null when it missed or the roster guard rejected the hit. */
    private fun groundLocal(query: String, retrievalQuery: String): Grounding? {
        // anchorQuery = the RAW user query: the exact name-anchor tier must NOT see the
        // cross-language aliases enrichQuery appended, or a variant's alias ("The Herta")
        // re-introduces the base name ("Herta") and anchors the wrong character's docs.
        val local = runCatching { tools.lookupHsr(retrievalQuery, anchorQuery = query) }.getOrElse {
            log.warn("lookupHsr threw during grounding for '{}'", query, it)
            emptyMap()
        }
        if (local["found"] != true) return null
        val ctx = formatLocal(local)
        if (passesRosterGuard(query, ctx)) return Grounding(true, ctx, Grounding.Source.LOCAL)
        log.debug("Roster guard rejected local hit for '{}' (subject absent from chunks)", query)
        return null
    }

    /** Web tier; null when it missed or the roster guard rejected the hit. */
    private fun groundWeb(query: String, retrievalQuery: String, recent: Boolean, onWebSearch: () -> Unit): Grounding? {
        onWebSearch()
        val web = runCatching { tools.searchWeb(retrievalQuery, recent) }.getOrElse {
            log.warn("searchWeb threw during grounding for '{}'", query, it)
            emptyMap()
        }
        if (web["found"] != true) return null
        val ctx = formatWeb(web)
        if (ctx.isNotBlank() && passesRosterGuard(query, ctx)) return Grounding(true, ctx, Grounding.Source.WEB)
        log.debug("Roster guard rejected web hit for '{}' (subject absent from page text)", query)
        return null
    }

    /**
     * True unless this is an entity question and SOME asked-about name never appears in
     * [context]. Every name in a "quem é X? quem é Y?" (or "X, Y e Z") batch must be grounded;
     * one ungrounded name fails the whole answer, because the model will otherwise fuse real
     * retrieved lore onto the fake name (that's the "Cora/Carmilla/Sylph" failure).
     *
     * A subject that resolves to a known character is also checked under its OTHER
     * localized names: "quem é March 7th?" grounds against a chunk that says "Março 7".
     * Fake names resolve to nothing, so they gain no aliases and fail exactly as before.
     */
    private fun passesRosterGuard(query: String, context: String): Boolean {
        val subjects = entitySubjects(query)
        if (subjects.isEmpty()) return true
        return subjects.all { s ->
            val aliases = characters.resolveId(s)?.let { characters.byId(it)?.names }.orEmpty()
            subjectGrounded(s, context, aliases)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun formatLocal(map: Map<String, Any?>): String {
        val results = map["results"] as? List<Map<String, Any?>> ?: return ""
        return results.joinToString("\n\n") { r ->
            val header = listOfNotNull(r["name"], r["category"]).joinToString(" • ").ifBlank { "—" }
            "[$header]\n${r["content"]}"
        }.trim()
    }

    @Suppress("UNCHECKED_CAST")
    private fun formatWeb(map: Map<String, Any?>): String {
        val results = map["results"] as? List<Map<String, Any?>> ?: return ""
        return results.joinToString("\n\n") { r ->
            // Prefer the full page text; fall back to the snippet when the fetch was empty.
            val body = (r["content"] as? String)?.takeIf { it.isNotBlank() } ?: (r["snippet"] as? String).orEmpty()
            "[${r["title"]}] (${r["url"]})\n$body"
        }.trim()
    }

    internal companion object {
        /** "who/what is X" openers, the questions where existence itself is in doubt. */
        private val ENTITY_OPENER = Regex(
            "(quem (são|sao|é|e|eh|seria)|o que (é|e|eh)|who is|what is)\\s+",
            RegexOption.IGNORE_CASE,
        )

        /** Separators inside a name list: "cora, carmilla e sylph". */
        private val LIST_SEP = Regex(",| e | and |;", RegexOption.IGNORE_CASE)
        private val LEADING_ARTICLE = Regex("^(a|o|as|os|the)\\s+", RegexOption.IGNORE_CASE)
        private val WHITESPACE = Regex("\\s+")

        /**
         * Every entity name asked about, handling both repeated openers ("quem é X? quem é Y?")
         * and a single opener over a list ("quem é X, Y e Z?"). Empty when the query isn't an
         * entity question (builds, teams, mechanics — the roster guard doesn't apply to those).
         * Pure, so the whole guard is unit-testable without a vector store.
         */
        internal fun entitySubjects(query: String): List<String> {
            val parts = query.split(ENTITY_OPENER)
            if (parts.size <= 1) return emptyList()
            return parts.drop(1).flatMap { seg ->
                // The name(s) run until the question mark / period that ends this sub-question.
                val head = seg.substringBefore('?').substringBefore('.').trim()
                head.split(LIST_SEP).map { cleanName(it) }.filter { it.isNotBlank() }
            }
        }

        private fun cleanName(raw: String): String =
            LEADING_ARTICLE.replace(raw.trim(), "").trim().trimEnd('?', '.', '!', ',', ';').trim()

        /**
         * True only when EVERY significant token (>= 3 chars) of [subject] appears in [context]
         * as a whole word. Two deliberate choices, each fixing a way a fake name slipped through:
         *  - whole-word + Unicode-aware ((?iuU)\b…\b): a plain substring match finds "cora"
         *    inside "coração", which is precisely how "Cora" passed against real lore text;
         *  - ALL tokens, not any: stops one common token ("nova" in "vida nova") from grounding a
         *    fabricated multi-word name.
         * A subject with no token >= 3 chars can't be judged, so it passes — the guard only ever
         * fires on a clear absence, never suppressing a legitimately-new character the source names.
         */
        internal fun subjectMentioned(subject: String, context: String): Boolean {
            val tokens = subject.split(WHITESPACE).filter { it.length >= 3 }
            if (tokens.isEmpty()) return true
            return tokens.all { wordPresent(it, context) }
        }

        private fun wordPresent(token: String, context: String): Boolean =
            Regex("(?iuU)\\b" + Regex.escape(token) + "\\b").containsMatchIn(context)

        /**
         * [subjectMentioned] extended with localized [aliases]: the subject counts as grounded
         * when the context names it directly OR under any known alias of the character it
         * resolved to. Pure, so the cross-language pass is unit-testable without a DB.
         */
        internal fun subjectGrounded(subject: String, context: String, aliases: List<String>): Boolean =
            subjectMentioned(subject, context) ||
                aliases.any { it.isNotBlank() && subjectMentioned(it, context) }

        /** Word tokens whose presence means the answer likely isn't in a static local KB. */
        private val WEB_CUE_TOKENS = setOf(
            // explicit "search the internet" requests
            "internet", "web", "online", "google",
            // recency / not-yet-released content
            "novo", "nova", "novos", "novas",
            "atual", "atuais", "proximo", "proxima", "proximos", "proximas",
            "futuro", "futura", "futuros", "futuras", "breve",
            "banner", "banners", "patch", "versao", "versoes",
        )

        /** Stem prefixes covering the morphology of announce/leak/release verbs. */
        private val WEB_CUE_PREFIXES = listOf("anunciad", "anuncio", "vazad", "vazament", "leak", "lancad", "lancament")

        /** A version number ("4.6") — patch talk, inherently about current/future content. */
        private val VERSION_NUMBER = Regex("\\b\\d+\\.\\d+\\b")

        private val TOKEN_SEP = Regex("[^\\p{L}\\p{N}]+")

        /**
         * True when the question should be grounded on the WEB before the local KB: the user
         * explicitly asked for an internet search, or the subject is announced/leaked/new/
         * versioned content a static KB can't know. Known [entityNames] are stripped before
         * the cue scan — the "nova" in "Himeko Nova" is a character the KB knows, not recency
         * talk; the same word standing alone ("teve personagem nova?") still cues. Deliberately
         * broad — this replaces the "searchWeb even if lookupHsr hit" rule the old brain prompt
         * had, and a false positive only reorders the tiers (local remains the fallback), never
         * loses data. Pure, so the routing is unit-testable without tools.
         */
        internal fun wantsWeb(query: String, entityNames: Collection<String> = emptyList()): Boolean {
            // Version numbers scan the RAW query: normalize folds punctuation, turning "3.5"
            // into "3 5" before the regex could see it.
            if (VERSION_NUMBER.containsMatchIn(query)) return true
            val norm = HsrCharacterService.stripNames(query, entityNames)
            return norm.split(TOKEN_SEP).any { t ->
                t in WEB_CUE_TOKENS || WEB_CUE_PREFIXES.any { p -> t.startsWith(p) }
            }
        }

        /**
         * Search-engine query for a news-shaped question. Leak/announcement content lives on
         * ENGLISH keyword-indexed pages, so a PT-BR sentence ("fiquei sabendo que teve novos
         * personagens anunciados…") is the worst possible query for it. When the question
         * carries anchors — known character names and/or version numbers — the query becomes
         * `<anchors> new announcement leak`; without anchors we can't do better statically,
         * so the original question just gets the EN news terms appended. Pure.
         */
        internal fun newsQuery(query: String, characterEnNames: List<String>): String {
            val versions = VERSION_NUMBER.findAll(query).map { it.value }
            val anchors = (characterEnNames.filter { it.isNotBlank() } + versions).distinct()
            return if (anchors.isEmpty()) "$query new announcement leak"
            else anchors.joinToString(" ") + " new announcement leak"
        }

        /**
         * Appends to [query] whichever [aliases] it doesn't already contain (accent/case-
         * insensitive whole-word check, so "acheron" in the query suppresses "Acheron").
         * Pure; returns [query] untouched when there is nothing to add.
         */
        internal fun enrichQuery(query: String, aliases: List<String>): String {
            val qNorm = HsrCharacterService.normalize(query)
            val extras = aliases.asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .filterNot { HsrCharacterService.containsWord(qNorm, HsrCharacterService.normalize(it)) }
                .toList()
            return if (extras.isEmpty()) query else "$query (${extras.joinToString(", ")})"
        }
    }
}
