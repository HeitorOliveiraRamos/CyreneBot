package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.cyrene.hsr.SrsNanokaPopulator
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
 * When they diverge and `bot.knowledge.auto-reindex` is on (the default), it runs the FULL
 * refresh right here, in order: re-harvest the V17 tables ([SrsNanokaPopulator], the single
 * upstream fetch) and then re-render + re-embed the vector store from them
 * ([HsrKnowledgeIngestion]). Synchronously on the scheduler thread, which also blocks the other
 * @Scheduled ticks for the few minutes it takes (fine at a ~6-weekly patch cadence). With
 * auto-reindex off it only WARNs, and the refresh stays the manual `POPULATE_SRS_NANOKA=true`
 * + `HSR_REINDEX=true` runs.
 *
 * This is also the cold-start bootstrap: on a fresh DB nothing has been indexed, so the first
 * check (5 min after startup) harvests the tables and builds the store from scratch.
 */
@Component
class KbFreshnessCheck(
    private val nanoka: NanokaIngestionSource,
    private val jdbcTemplate: JdbcTemplate,
    private val ingestion: HsrKnowledgeIngestion,
    private val populator: SrsNanokaPopulator,
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

    /** Re-harvest then reindex when auto-reindex is on; otherwise the old warn-only behaviour. */
    private fun staleAction(reason: String) {
        if (!properties.knowledge.autoReindex) {
            log.warn("{} Rode com POPULATE_SRS_NANOKA=true e depois HSR_REINDEX=true para atualizar.", reason)
            return
        }
        log.warn("{} Recolhendo e reindexando automaticamente (HSR_AUTO_REINDEX=false desativa)...", reason)
        try {
            // Order matters: the store is rendered FROM the tables, so they refresh first.
            // Keep-last-good on both sides — populate() aborts below its sanity floor (keeping the
            // previous rows) and reindex() loads before it truncates, so a failure at either step
            // leaves a working KB and the next daily tick retries.
            populator.populate()
            ingestion.reindex()
        } catch (e: Exception) {
            log.error("Refresh automático falhou — mantendo a base atual e tentando no próximo ciclo.", e)
        }
    }
}
