package com.cyrene.knowledge

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
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun ground(query: String): Grounding {
        val local = runCatching { tools.lookupHsr(query) }.getOrElse {
            log.warn("lookupHsr threw during grounding for '{}'", query, it)
            emptyMap()
        }
        if (local["found"] == true) {
            val ctx = formatLocal(local)
            if (passesRosterGuard(query, ctx)) return Grounding(true, ctx, Grounding.Source.LOCAL)
            log.debug("Roster guard rejected local hit for '{}' (subject absent from chunks)", query)
        }

        val web = runCatching { tools.searchWeb(query) }.getOrElse {
            log.warn("searchWeb threw during grounding for '{}'", query, it)
            emptyMap()
        }
        if (web["found"] == true) {
            val ctx = formatWeb(web)
            if (ctx.isNotBlank() && passesRosterGuard(query, ctx)) return Grounding(true, ctx, Grounding.Source.WEB)
            log.debug("Roster guard rejected web hit for '{}' (subject absent from page text)", query)
        }

        return Grounding.EMPTY
    }

    /** True unless this is a bare entity question whose subject never appears in [context]. */
    private fun passesRosterGuard(query: String, context: String): Boolean {
        val subject = entitySubject(query) ?: return true
        return subjectMentioned(subject, context)
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
        /** Bare "who/what is X" openers, the questions where existence itself is in doubt. */
        private val ENTITY_Q = Regex(
            "^\\s*(quem (é|e|eh|seria)|o que (é|e|eh)|who is|what is)\\s+",
            RegexOption.IGNORE_CASE,
        )
        private val LEADING_ARTICLE = Regex("^(a|o|as|os|the)\\s+", RegexOption.IGNORE_CASE)

        /**
         * Extracts the subject of a bare entity question ("quem é lilita?" → "lilita"), or null
         * when the query isn't that shape (builds, teams, mechanics — the roster guard doesn't
         * apply to those). Pure, so the guard is unit-testable without a vector store.
         */
        internal fun entitySubject(query: String): String? {
            val m = ENTITY_Q.find(query) ?: return null
            val rest = query.substring(m.range.last + 1)
                .trim()
                .trimEnd('?', '.', '!', ',', ';')
                .let { LEADING_ARTICLE.replace(it, "") }
                .trim()
            return rest.takeIf { it.isNotBlank() }
        }

        /**
         * True when at least one significant token of [subject] appears in [context]. Tokens
         * shorter than 3 chars are ignored (articles/particles), and an all-short subject
         * can't be judged so it passes — the guard only ever fires on a clear absence, never
         * on uncertainty, so it can't suppress a legitimately-new character the source names.
         */
        internal fun subjectMentioned(subject: String, context: String): Boolean {
            val tokens = subject.split(Regex("\\s+")).filter { it.length >= 3 }
            if (tokens.isEmpty()) return true
            return tokens.any { context.contains(it, ignoreCase = true) }
        }
    }
}
