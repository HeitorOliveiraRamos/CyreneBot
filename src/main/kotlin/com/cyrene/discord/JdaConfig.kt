package com.cyrene.discord

import com.cyrene.config.BotProperties
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Builds the [JDA] client as a Spring bean so other beans (e.g. tool implementations)
 * can inject it directly. Listener registration and the readiness wait are handled by
 * [DiscordBootstrap] right after this bean is constructed; no events are dispatched
 * until then because we don't call `awaitReady` here.
 *
 * The bean's `destroyMethod` is intentionally empty — [DiscordBootstrap.stop] performs
 * the orderly shutdown so it can also flush in-flight `queue()` callbacks.
 */
@Configuration
class JdaConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean(destroyMethod = "")
    fun jda(properties: BotProperties): JDA {
        log.info("Building JDA client")
        return JDABuilder.createDefault(
            properties.token,
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.DIRECT_MESSAGES,
            GatewayIntent.GUILD_MEMBERS,
        ).build()
    }
}
