package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * One-shot loader that rebuilds the Honkai: Star Rail vector store from
 * [NanokaIngestionSource] (structured JSON from [nanoka.cc](https://hsr.nanoka.cc)).
 *
 * Run-once by design: it only exists as a bean when `bot.knowledge.reindex=true`, so normal
 * bot startups don't touch it. To (re)build the knowledge base after a new patch:
 *
 * ```
 * HSR_REINDEX=true mvn spring-boot:run
 * # ...wait for "HSR reindex complete", Ctrl-C, then run normally.
 * ```
 *
 * The version is auto-discovered from nanoka's home page; pin it with `HSR_NANOKA_VERSION` to
 * freeze a patch. nanoka is the sole source — it carries fuller, current-per-patch kits than
 * the old Kaggle CSV dump, so this truncates `vector_store` and re-embeds purely from it.
 */
@Component
@ConditionalOnProperty(name = ["bot.knowledge.reindex"], havingValue = "true")
class HsrKnowledgeIngestion(
    private val vectorStore: VectorStore,
    private val jdbcTemplate: JdbcTemplate,
    private val properties: BotProperties,
    private val nanoka: NanokaIngestionSource,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(vararg args: String?) {
        val docs = nanoka.load()
        if (docs.isEmpty()) {
            log.error("Parsed 0 usable documents from nanoka — aborting before clearing the store.")
            return
        }

        log.info("Clearing vector_store and embedding {} documents...", docs.size)
        jdbcTemplate.execute("TRUNCATE TABLE vector_store")
        embedInBatches(docs)
        recordIndexedVersion()
        log.info("HSR reindex complete: {} documents embedded. Restart WITHOUT HSR_REINDEX to run normally.", docs.size)
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
}
