package com.cyrene.ai

import com.cyrene.config.BotProperties
import org.springframework.stereotype.Component

@Component
class ResponsePostProcessor(private val properties: BotProperties) {

    private val thinkBlock = Regex("<think>[\\s\\S]*?</think>")

    fun process(raw: String): String {
        var content = raw
        if (properties.modelName.contains("deepseek", ignoreCase = true)) {
            content = thinkBlock.replace(content, "").trim()
        }
        return content
    }
}
