package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Daily staleness check for the HSR knowledge base: compares the nanoka data version the
 * store was last indexed with (`kb_meta.hsr_versao_indexada`, written by
 * [HsrKnowledgeIngestion]) against the version nanoka currently serves.
 *
 * When they diverge and `bot.knowledge.auto-reindex` is on (the default), it triggers the
 * rebuild right here — synchronously on the scheduler thread, which also blocks the other
 * @Scheduled ticks for the few minutes the embed takes (fine at a ~6-weekly patch cadence).
 * With auto-reindex off it only WARNs, and the rebuild stays a manual `HSR_REINDEX=true` run.
 */
@Component
class KbFreshnessCheck(
    private val nanoka: NanokaIngestionSource,
    private val jdbcTemplate: JdbcTemplate,
    private val ingestion: HsrKnowledgeIngestion,
    private val properties: BotProperties,
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
            live -> log.debug("HSR KB em dia (versão {})", live)
            null -> staleAction(
                "HSR KB: nenhuma versão indexada registrada (base nunca reindexada com este build?). " +
                    "Versão live do nanoka: $live.",
            )
            else -> staleAction("HSR KB DESATUALIZADA: indexada=$indexed live=$live — saiu patch novo.")
        }
    }

    /** Reindex in place when auto-reindex is on; otherwise the old warn-only behaviour. */
    private fun staleAction(reason: String) {
        if (!properties.knowledge.autoReindex) {
            log.warn("{} Rode com HSR_REINDEX=true para atualizar.", reason)
            return
        }
        log.warn("{} Reindexando automaticamente (HSR_AUTO_REINDEX=false desativa)...", reason)
        try {
            ingestion.reindex()
        } catch (e: Exception) {
            // Keep-last-good: the load-before-truncate order inside reindex() means a failure
            // here leaves whatever KB existed; next daily tick retries.
            log.error("Reindex automático falhou — mantendo a base atual e tentando no próximo ciclo.", e)
        }
    }
}
