package com.cyrene

import com.cyrene.config.BotProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [BotProperties::class])
class CyreneBotApplication

fun main(args: Array<String>) {
    runApplication<CyreneBotApplication>(*args)
}
