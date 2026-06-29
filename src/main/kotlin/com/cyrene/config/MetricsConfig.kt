package com.cyrene.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides an in-memory [MeterRegistry]. The bot runs with `web-application-type: none`, so
 * there is no actuator/Prometheus endpoint to scrape — a [SimpleMeterRegistry] keeps the
 * metrics in process, where [com.cyrene.ai.AiMetrics] periodically logs a compact summary.
 * Swap this bean for a real registry (Prometheus, OTLP) if a scrape target is ever added.
 */
@Configuration
class MetricsConfig {

    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}
