package com.cyrene.knowledge

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Daily staleness check for the HSR knowledge base: compares the nanoka data version the
 * store was last indexed with (`kb_meta.hsr_versao_indexada`, written by
 * [HsrKnowledgeIngestion]) against the version nanoka currently serves, and logs a WARN
 * when they diverge — i.e. a new patch landed and the KB is answering from the old one.
 *
 * Warn-only by design: a reindex is a long embed run best triggered deliberately
 * (`HSR_REINDEX=true`), not at a random scheduled moment while the bot is serving replies.
 */
@Component
class KbFreshnessCheck(
    private val nanoka: NanokaIngestionSource,
    private val jdbcTemplate: JdbcTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** First check 5 min after startup (off the critical path), then daily. */
    @Scheduled(initialDelay = 5, fixedDelay = 24 * 60, timeUnit = TimeUnit.MINUTES)
    fun check() {
        val live = nanoka.resolveVersion() ?: run {
            log.debug("KB freshness: nanoka version unavailable; skipping this check")
            return
        }
        val indexed = jdbcTemplate
            .queryForList("SELECT valor FROM kb_meta WHERE chave = 'hsr_versao_indexada'", String::class.java)
            .firstOrNull()
        when (indexed) {
            null -> log.warn(
                "HSR KB: nenhuma versão indexada registrada (base nunca reindexada com este build?). " +
                    "Versão live do nanoka: {}. Rode com HSR_REINDEX=true para (re)construir.", live,
            )
            live -> log.debug("HSR KB em dia (versão {})", live)
            else -> log.warn(
                "HSR KB DESATUALIZADA: indexada={} live={} — saiu patch novo. Rode com HSR_REINDEX=true para atualizar.",
                indexed, live,
            )
        }
    }
}
