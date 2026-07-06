package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.cyrene.hsr.HsrCharacterService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Exact-match cache of final knowledge answers, keyed by the normalized condensed question
 * ("Quem é a Acheron?" == "quem e a acheron"). A hit skips retrieval, voice AND verify —
 * the whole pipeline — so popular repeat questions cost zero LLM calls.
 *
 * Correctness rules, all enforced here:
 *  - only grounded, verified answers are stored (callers never put an abstain);
 *  - an answer containing the asking user's name is NOT stored — a cached vocative must
 *    not leak one user's name into another user's reply;
 *  - LOCAL answers live until the next reindex ([HsrKnowledgeIngestion] truncates the
 *    table); WEB answers additionally expire after 24h (leaks/banners change fast).
 */
// ponytail: exact-match keys, not embedding similarity — near-identical questions with
// different answers ("e1 da acheron" / "e2 da acheron") must never collide. Revisit with a
// semantic layer only if the hit rate disappoints in the logs.
@Component
class AnswerCache(
    private val jdbc: JdbcTemplate,
    private val properties: BotProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Cached answer for [question], or null on miss/disabled/error. Fail-open: never throws. */
    fun get(question: String): String? {
        if (!properties.knowledge.answerCache) return null
        val key = normalizeKey(question)
        if (key.isEmpty()) return null
        return try {
            jdbc.queryForList(
                """
                SELECT resposta FROM resposta_cache
                WHERE pergunta_norm = ? AND (fonte = 'LOCAL' OR criado_em > now() - interval '24 hours')
                """.trimIndent(),
                String::class.java,
                key,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("Answer cache read failed for '{}': {}", key, e.message)
            null
        }
    }

    /** Stores a grounded answer unless it embeds the asker's name. Fail-open: never throws. */
    fun put(question: String, answer: String, source: Grounding.Source, userName: String?) {
        if (!properties.knowledge.answerCache) return
        if (!shouldStore(answer, userName)) {
            log.debug("Answer cache skip: reply carries the user's name")
            return
        }
        val key = normalizeKey(question)
        if (key.isEmpty()) return
        try {
            jdbc.update(
                """
                INSERT INTO resposta_cache (pergunta_norm, resposta, fonte) VALUES (?, ?, ?)
                ON CONFLICT (pergunta_norm)
                DO UPDATE SET resposta = EXCLUDED.resposta, fonte = EXCLUDED.fonte, criado_em = now()
                """.trimIndent(),
                key, answer, source.name,
            )
        } catch (e: Exception) {
            log.warn("Answer cache write failed for '{}': {}", key, e.message)
        }
    }

    internal companion object {
        /** Same "[name]: " speaker tag the mention path prepends to turns (see OllamaAiService). */
        private val SPEAKER_PREFIX = Regex("^\\[[^\\]]*]:\\s*")
        private val PUNCT = Regex("[^\\p{L}\\p{N} ]")
        private val SPACES = Regex("\\s+")

        /**
         * Cache key: speaker tag stripped, accents/case folded, punctuation dropped,
         * whitespace collapsed — so trivially-different phrasings of the same question
         * ("Quem é a Acheron?", "[Heitor]: quem e a acheron") share one entry. Pure.
         */
        internal fun normalizeKey(question: String): String =
            SPACES.replace(
                PUNCT.replace(HsrCharacterService.normalize(SPEAKER_PREFIX.replace(question.trim(), "")), " "),
                " ",
            ).trim()

        /** False when the rendered answer contains the asking user's name (vocative leak). Pure. */
        internal fun shouldStore(answer: String, userName: String?): Boolean =
            userName.isNullOrBlank() || !answer.contains(userName, ignoreCase = true)
    }
}
