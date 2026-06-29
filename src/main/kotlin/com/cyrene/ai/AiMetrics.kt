package com.cyrene.ai

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Lightweight metrics seam for the LLM pipeline. Each pass is wrapped in [timePass] so its
 * latency lands in a per-pass [Timer] (`cyrene.llm.pass{pass=…}`); routing and gate events go
 * through [count]. The bot has no metrics-scrape endpoint, so [logSummary] dumps a compact
 * snapshot to the log every few minutes — and only when something actually happened — so the
 * latency tradeoffs from the brain/voice split and the web-fetch parallelism are observable
 * without standing up Prometheus.
 */
@Component
class AiMetrics(private val registry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Times [block], recording its duration into the per-pass timer tagged with [pass]. */
    fun <T> timePass(pass: String, block: () -> T): T {
        val sample = Timer.start(registry)
        try {
            return block()
        } finally {
            sample.stop(registry.timer(PASS_TIMER, "pass", pass))
        }
    }

    /** Increments a tagged counter (e.g. inference-gate acquired/rejected, fast-path hits). */
    fun count(name: String, tagKey: String, tagValue: String) {
        registry.counter(name, tagKey, tagValue).increment()
    }

    /**
     * Logs a one-line-per-category snapshot of timers and counters every [SUMMARY_INTERVAL_MS].
     * No-ops when nothing has been recorded, so an idle bot stays quiet.
     */
    @Scheduled(fixedDelay = SUMMARY_INTERVAL_MS, initialDelay = SUMMARY_INTERVAL_MS)
    fun logSummary() {
        val timers = registry.meters.filterIsInstance<Timer>().filter { it.count() > 0 }
        val counters = registry.meters.filterIsInstance<Counter>().filter { it.count() > 0 }
        if (timers.isEmpty() && counters.isEmpty()) return

        if (timers.isNotEmpty()) {
            val line = timers.joinToString("  |  ") { t ->
                val label = t.id.getTag("pass") ?: t.id.name
                "%s n=%d mean=%.0fms max=%.0fms".format(
                    label, t.count(), t.mean(TimeUnit.MILLISECONDS), t.max(TimeUnit.MILLISECONDS),
                )
            }
            log.info("LLM pass metrics — {}", line)
        }
        if (counters.isNotEmpty()) {
            val line = counters.joinToString("  |  ") { c ->
                val tags = c.id.tags.joinToString(",") { "${it.key}=${it.value}" }
                "${c.id.name}{$tags}=${c.count().toLong()}"
            }
            log.info("Counters — {}", line)
        }
    }

    private companion object {
        const val PASS_TIMER = "cyrene.llm.pass"
        const val SUMMARY_INTERVAL_MS = 300_000L
    }
}
