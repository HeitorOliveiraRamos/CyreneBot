package com.cyrene.ai

import org.springframework.stereotype.Component

@Component
class ResponsePostProcessor {

    private val thinkBlock = Regex("<think>[\\s\\S]*?</think>")

    /**
     * Strips chain-of-thought `<think>…</think>` blocks unconditionally. Reasoning models
     * emit them — deepseek, and Qwen3 with thinking mode on — and they must never reach
     * Discord. Stripping is safe for models that don't emit the tag (the regex just doesn't
     * match), so we no longer gate it on the model name (previously deepseek-only).
     */
    fun process(raw: String): String =
        thinkBlock.replace(raw, "").trim()
}
