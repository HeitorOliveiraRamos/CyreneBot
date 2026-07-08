package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.cyrene.hsr.HsrCharacterService
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.CommandLineRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rebuilds the Honkai: Star Rail vector store from two structured-JSON sources:
 * [StarRailStationIngestionSource] (PT-BR profiles/skills/eidolons/light cones/relic sets) and
 * [NanokaIngestionSource] (English builds, enemies and major traces — the categories srs doesn't
 * serve). srs owns the high-value, most-queried kit docs in real Portuguese; nanoka fills the gaps.
 *
 * Two triggers, same [reindex] body:
 *  - startup, when `bot.knowledge.reindex=true` (`HSR_REINDEX=true mvn spring-boot:run`) —
 *    the manual, run-once path;
 *  - [KbFreshnessCheck], when `bot.knowledge.auto-reindex` is on and nanoka starts serving
 *    a newer data version than the one indexed — the hands-off path. srs's deploy moves with the
 *    same patch, so nanoka's version signal doubles as the trigger for both.
 *
 * Documents are loaded BEFORE the truncate, so a broken fetch never wipes a working KB. If
 * starrailstation is unreachable, [reindex] falls back to FULL (English) nanoka rather than lose
 * profiles/skills/eidolons entirely — degraded, but never empty.
 */
@Component
class HsrKnowledgeIngestion(
    private val vectorStore: VectorStore,
    private val jdbcTemplate: JdbcTemplate,
    private val properties: BotProperties,
    private val srs: StarRailStationIngestionSource,
    private val nanoka: NanokaIngestionSource,
    private val characters: HsrCharacterService,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Guards against overlapping rebuilds (startup runner vs. the scheduled check). */
    private val running = AtomicBoolean(false)

    override fun run(vararg args: String?) {
        if (!properties.knowledge.reindex) return
        reindex()
        log.info("Restart WITHOUT HSR_REINDEX to run normally.")
    }

    /** Full rebuild: load from nanoka, truncate, embed, record version. Safe to call live. */
    fun reindex() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Reindex já em andamento — ignorando pedido concorrente.")
            return
        }
        try {
            // srs (PT) owns profile/skill/eidolon/light_cone/relic_set; nanoka fills the rest.
            // srs down → full English nanoka rather than lose the core kit docs. Loaded before the
            // truncate so a broken source never wipes a working KB. Nanoka's docs render names
            // through the srs PT maps (+ hsr_character for team members) so build docs list items
            // by the SAME strings as the PT item docs — the effectDocs join depends on it.
            val srsData = srs.load()
            val nanokaDocs = nanoka.load(srsData.relicNames, srsData.coneNames, characters.ptNames())
            val docs = if (srsData.docs.isEmpty()) {
                log.warn("starrailstation returned 0 documents — falling back to full (English) nanoka.")
                nanokaDocs
            } else {
                srsData.docs + nanokaDocs.filter { it.metadata["category"] in NANOKA_ONLY_CATEGORIES }
            }
            if (docs.isEmpty()) {
                log.error("Parsed 0 usable documents from both sources — aborting before clearing the store.")
                return
            }

            log.info("Clearing vector_store and embedding {} documents...", docs.size)
            jdbcTemplate.execute("TRUNCATE TABLE vector_store")
            embedInBatches(docs)
            recordIndexedVersion()
            // Cached answers were produced from the OLD index — drop them all.
            jdbcTemplate.execute("TRUNCATE TABLE resposta_cache")
            log.info("HSR reindex complete: {} documents embedded; answer cache cleared.", docs.size)
        } finally {
            running.set(false)
        }
    }

    /**
     * Records which nanoka data version this reindex embedded, so [KbFreshnessCheck] can
     * warn when a new patch lands. Re-resolves the version (one extra tiny home-page fetch
     * on a manual, run-once path); a null resolution here is only logged — the embed
     * already succeeded and must not be rolled back over bookkeeping.
     */
    private fun recordIndexedVersion() {
        val version = nanoka.resolveVersion()
        if (version == null) {
            log.warn("Could not re-resolve the nanoka version to record it; freshness checks will nag until the next reindex.")
            return
        }
        jdbcTemplate.update(
            """
            INSERT INTO kb_meta (chave, valor, atualizado_em) VALUES ('hsr_versao_indexada', ?, now())
            ON CONFLICT (chave) DO UPDATE SET valor = EXCLUDED.valor, atualizado_em = now()
            """.trimIndent(),
            version,
        )
        log.info("Recorded indexed HSR data version {}", version)
    }

    private fun embedInBatches(docs: List<Document>) {
        val batchSize = properties.knowledge.batchSize.coerceAtLeast(1)
        val batches = docs.chunked(batchSize)
        batches.forEachIndexed { i, batch ->
            vectorStore.add(batch)
            log.info("  embedded batch {}/{} ({} docs)", i + 1, batches.size, batch.size)
        }
    }

    private companion object {
        /** Categories starrailstation can't serve, so nanoka keeps them (see class kdoc). */
        private val NANOKA_ONLY_CATEGORIES = setOf("build", "enemy", "trace")
    }
}
