package com.cyrene.ai

import com.cyrene.config.BotProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore

/**
 * Bounds how many reply pipelines may run their LLM passes at once.
 *
 * A single local Ollama serializes generations internally, so without a ceiling a burst of
 * @-mentions all get scheduled on the AI executor and pile up behind each other — every
 * reply then crawls and users wait far longer than if the bot had just said "one moment".
 * This gate caps concurrency at [BotProperties.Performance.maxConcurrentInferences]; callers
 * [tryAcquire] a permit before starting and [release] it when the pipeline finishes (success
 * or failure). When no permit is free the caller short-circuits to an immediate "busy" reply
 * instead of joining the queue.
 *
 * The semaphore is fair, so waiters (should a caller ever choose to block) are served in
 * order; today callers only [tryAcquire] non-blockingly, so fairness mainly documents intent.
 */
@Component
class InferenceGate(
    properties: BotProperties,
    private val metrics: AiMetrics,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val permits = properties.performance.maxConcurrentInferences.coerceAtLeast(1)
    private val semaphore = Semaphore(permits, true)

    init {
        log.info("InferenceGate initialised with {} concurrent permit(s)", permits)
    }

    /** Tries to claim a permit without blocking. Returns false when the gate is full. */
    fun tryAcquire(): Boolean {
        val acquired = semaphore.tryAcquire()
        metrics.count("cyrene.inference_gate", "result", if (acquired) "acquired" else "rejected")
        return acquired
    }

    /** Returns a previously acquired permit. Call exactly once per successful [tryAcquire]. */
    fun release() {
        semaphore.release()
    }

    /** Permits currently free — for diagnostics/metrics only. */
    val available: Int get() = semaphore.availablePermits()
}
