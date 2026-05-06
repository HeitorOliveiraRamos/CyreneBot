package com.cyrene.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
class AsyncConfig {

    /**
     * Single shared executor for background work (Ollama calls, AI moderation, summaries).
     * Replaces the five per-listener cached pools the legacy bot created.
     */
    @Bean(destroyMethod = "shutdown")
    fun aiExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 32
        queueCapacity = 64
        setThreadNamePrefix("cyrene-ai-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(5)
        initialize()
    }
}
