package com.cyrene.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bot")
data class BotProperties(
    val token: String,
    val personalityFile: String = "classpath:prompts/cyrene-personality.md",
    /**
     * The default Ollama model used by legacy single-pass paths (e.g. `/contexto-do-canal`).
     * MUST be a tool-capable model when tool calling is exercised — verified options
     * include llama3.1, llama3.2, qwen2.5, mistral-nemo.
     */
    val modelName: String,
    /**
     * Model used by the "brain" pass — decides whether to call a Discord tool and
     * produces a factual, neutral description of what was done. MUST be tool-capable.
     * Today defaults to llama3.1; can be swapped to a smaller/faster model later without
     * touching the voice pass.
     */
    val brainModelName: String = "llama3.1",
    /**
     * Model used by the "voice" pass — rewrites the brain's factual output as Cyrene,
     * in persona, without tool callbacks. Today defaults to llama3.1; can later be
     * swapped to a model tuned for prose/persona quality independent of the brain.
     */
    val voiceModelName: String = "llama3.1",
    val reply: Reply = Reply(),
    val message: Message = Message(),
    val context: Context = Context(),
    val performance: Performance = Performance(),
    /**
     * When set to a non-blank Discord channel ID, the message listeners
     * ([com.cyrene.discord.listener.MentionReplyListener] and
     * [com.cyrene.discord.listener.ChatSessionListener]) ignore messages from any other
     * channel. Used to scope the bot to a single staging channel during local testing.
     * Leave unset/blank in production to listen everywhere.
     */
    val testChannelId: String? = null,
) {
    data class Reply(
        val cooldownSeconds: Long = 5,
        val chain: ReplyChain = ReplyChain(),
    )

    /**
     * Tuning for the per-mention reply-chain walker
     * ([com.cyrene.discord.ReplyChainResolver]). The walker reconstructs prior Discord
     * messages by following [net.dv8tion.jda.api.entities.Message.getMessageReference]
     * pointers upward so multi-user reply threads carry context into the LLM prompt.
     *
     *  - [maxHops]: hard cap on chain depth. Once exceeded the walk stops and whatever was
     *    collected is used.
     *  - [budgetMs]: wall-clock budget for the whole walk. REST fallbacks can be slow; if
     *    the budget is blown the walk stops and partial context is used.
     *  - [cacheTtlMinutes]: how long the bot's own sent messages stay in
     *    [com.cyrene.discord.util.BotReplyCache] so they can be served without REST.
     *  - [cacheMaxSize]: LRU size cap for that cache.
     */
    data class ReplyChain(
        val maxHops: Int = 50,
        val budgetMs: Long = 800,
        val cacheTtlMinutes: Long = 120,
        val cacheMaxSize: Int = 2000,
    )

    data class Message(
        val maxLength: Int = 2000,
        val canMentionHereAndEveryone: Boolean = false,
    )

    data class Context(
        val historyFetchSize: Int = 50,
    )

    /**
     * Ollama inference tuning applied to every chat/tool call. These map directly to the
     * Ollama options of the same name and override Spring AI's defaults because
     * [com.cyrene.ai.OllamaAiService] builds per-prompt [org.springframework.ai.ollama.api.OllamaOptions].
     *
     *  - [numCtx]: context window. Default 2048 is small but matches typical history sizes;
     *    raise only if you actually feed long prompts. Larger ctx allocates more KV cache.
     *  - [numPredict]: hard cap on generated tokens. Prevents runaway replies.
     *  - [numThread]: CPU threads. Ignored when the model fits fully on GPU; set to your
     *    physical core count if any layers spill to CPU.
     *  - [brainTemperature] / [brainTopP]: sampling for the brain pass and the intent
     *    gate. Both are classification-like — should be tight. Raising these makes the
     *    brain hallucinate tool calls more often, which is exactly what we want to avoid.
     *  - [voiceTemperature]: sampling for the persona (voice) pass. Higher than brain —
     *    natural prose benefits from some diversity. Too low → flat replies; too high →
     *    persona drift.
     */
    data class Performance(
        val numCtx: Int = 4096,
        val numPredict: Int = 512,
        val numThread: Int = 8,
        val brainTemperature: Double = 0.1,
        val brainTopP: Double = 0.5,
        val voiceTemperature: Double = 0.8,
    )
}
