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
    val mentionContext: MentionContext = MentionContext(),
    val userInfo: UserInfo = UserInfo(),
) {
    data class Reply(
        val cooldownSeconds: Long = 5,
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
     */
    data class Performance(
        val numCtx: Int = 4096,
        val numPredict: Int = 512,
        val numThread: Int = 8,
    )

    /**
     * Tuning for the rolling per-(user, channel) history used by the @-mention reply path
     * ([com.cyrene.conversation.MentionContextService]).
     *
     *  - [maxMessages]: how many of the most recent mention messages to feed to the LLM as
     *    context. Both user and assistant turns count; 10 ≈ 5 exchanges.
     *  - [recencyMinutes]: a hard recency floor — messages older than this are excluded
     *    from the context even if [maxMessages] would otherwise pull them in. Prevents
     *    yesterday's unrelated thread from polluting today's prompt.
     *  - [retentionHours]: rows older than this are deleted on the next write. Caps table
     *    growth without needing a scheduled job.
     */
    data class MentionContext(
        val maxMessages: Int = 10,
        val recencyMinutes: Long = 30,
        val retentionHours: Long = 24,
    )

    /**
     * Tuning for the cached per-(user, guild) profile populated on first mention
     * ([com.cyrene.conversation.UserInfoService]).
     *
     *  - [refreshCron]: when [com.cyrene.conversation.UserInfoRefreshScheduler] runs to
     *    re-sync names/roles/permissions from JDA. Default: daily at 04:00.
     *  - [personalityRefreshEveryExchanges]: after every N persisted exchanges, the
     *    personality summary is regenerated from the user's recent question/answer pairs.
     *  - [personalitySampleSize]: how many recent exchanges are fed to the summarizer.
     */
    data class UserInfo(
        val refreshCron: String = "0 0 4 * * *",
        val personalityRefreshEveryExchanges: Int = 10,
        val personalitySampleSize: Int = 10,
    )
}
