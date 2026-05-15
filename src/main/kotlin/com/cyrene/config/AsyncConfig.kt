package com.cyrene.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableScheduling
class AsyncConfig {

    /**
     * Single shared executor for background work (Ollama calls and summaries).
     * Replaces the five per-listener cached pools the legacy bot created.
     */
    @Primary @Bean(destroyMethod = "shutdown")
    fun aiExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 32
        queueCapacity = 64
        setThreadNamePrefix("cyrene-ai-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(5)
        initialize()
    }
}
