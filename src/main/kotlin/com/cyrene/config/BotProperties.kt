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
    /**
     * Vision-capable Ollama model (e.g. qwen2.5vl, llava, llama3.2-vision) used by
     * [com.cyrene.ai.VisionService] to extract the content of image attachments as text
     * before the normal pipeline runs. Blank/unset = vision disabled: images are ignored
     * and the bot replies to the text only, exactly as before.
     */
    val visionModelName: String? = null,
    val reply: Reply = Reply(),
    val message: Message = Message(),
    val context: Context = Context(),
    val performance: Performance = Performance(),
    val knowledge: Knowledge = Knowledge(),
    /**
     * Directory of skill files (one .md per skill) for [com.cyrene.skills.SkillTools].
     * Relative to the working directory. Missing directory = feature off.
     */
    val skillsDir: String = "skills",
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
     *  - [numCtx]: context window. Default 16384 is sized for the knowledge path, whose
     *    worst case feeds ~2×18000 chars of fetched web text (see [Knowledge.webFetchCharLimit])
     *    plus the brain prompt and reply budget; a smaller ctx would silently truncate that
     *    web text and clip kit numbers. Lower it (and/or [Knowledge.webFetchPages]) only if
     *    you're memory-constrained and don't use deep web fetches — larger ctx grows the KV cache.
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
        val numCtx: Int = 16384,
        val numPredict: Int = 512,
        val numThread: Int = 8,
        /**
         * Ollama `keep_alive`: how long the model stays loaded after a call. Ollama's
         * default (5m) unloads the weights after a quiet spell, so the next mention pays
         * a full model load before the first token. Applied at every call site (like the
         * other knobs, per-prompt options replace the yaml defaults). "-1m" = keep forever.
         */
        val keepAlive: String = "30m",
        /**
         * Max number of mention/session replies that may run their LLM pipeline at once,
         * enforced by [com.cyrene.ai.InferenceGate]. A single local Ollama serializes
         * requests internally, so without a bound a burst of mentions piles up on the
         * executor and every reply slows to a crawl. When the gate is full, extra requests
         * get an immediate in-character "busy" reply instead of joining the queue. Keep this
         * small (default 2); raise it only if Ollama is configured for real parallelism
         * (OLLAMA_NUM_PARALLEL) and the host has the VRAM/RAM for concurrent generations.
         */
        val maxConcurrentInferences: Int = 2,
        val brainTemperature: Double = 0.1,
        val brainTopP: Double = 0.5,
        val voiceTemperature: Double = 0.8,
        /**
         * Token budget for the knowledge path — both the brain's HSR extraction and the
         * voice's persona rewrite of it. Much larger than [numPredict] because a full
         * character kit (every ability + multipliers + traces + Eidolons), and the
         * persona retelling of it, easily blow past the 512-token chat default that is
         * fine for a one-line moderation confirmation. Applied to the brain pass always
         * (it's only a ceiling — moderation replies still stop after a sentence) and to
         * the knowledge voice pass.
         */
        val knowledgeNumPredict: Int = 2048,
    )

    /**
     * Honkai: Star Rail RAG knowledge base. Backs the `lookupHsr` (local vector search)
     * and `searchWeb` (online fallback) tools in
     * [com.cyrene.discord.tools.GameKnowledgeTools], plus the one-shot ingestion runner
     * [com.cyrene.knowledge.HsrKnowledgeIngestion].
     *
     *  - [reindex]: when true, the ingestion runner wipes and rebuilds the vector store on
     *    startup. A run-once switch — keep false in normal operation.
     *  - [batchSize]: documents embedded+inserted per batch during ingestion.
     *  - [topK]: chunks returned to the brain per `lookupHsr` query.
     *  - [similarityThreshold]: minimum cosine similarity for a chunk to count as a hit;
     *    below it, `lookupHsr` reports nothing so the brain can fall back to `searchWeb`.
     *  - [searxngUrl]: optional self-hosted SearXNG JSON endpoint; blank disables web search.
     *  - [nanokaHomeUrl] / [nanokaCdnUrl] / [nanokaVersion]: source for the reindex
     *    ([NanokaIngestionSource]). The data version is auto-discovered from the home page;
     *    set [nanokaVersion] (blank/null = auto) to pin a specific patch.
     */
    data class Knowledge(
        val reindex: Boolean = false,
        /**
         * One-shot: when true, [com.cyrene.hsr.SrsNanokaPopulator] harvests the rich SRS+nanoka
         * schema (V17: personagem_hsr/reliquias/ornamentos_planos/cones_de_luz) on startup and
         * exits the runner. Off by default so normal boots are untouched; run once with
         * `POPULATE_SRS_NANOKA=true mvn spring-boot:run`, then restart without it.
         */
        val populateSrsNanoka: Boolean = false,
        /**
         * When true, the daily [com.cyrene.knowledge.KbFreshnessCheck] doesn't just WARN on
         * a version mismatch — it triggers a full reindex right there (load-then-truncate,
         * so a broken source never wipes a working KB). During the re-embed (a few minutes)
         * local lookups may return partial results; the abstain/web paths cover the gap.
         * Set false to go back to warn-only + manual HSR_REINDEX=true runs.
         */
        val autoReindex: Boolean = true,
        /**
         * When true, final knowledge answers are cached by normalized question
         * ([com.cyrene.knowledge.AnswerCache]) and repeat questions skip the whole
         * retrieve→voice→verify pipeline. Truncated on every reindex; web-sourced entries
         * also expire after 24h. Disable if replies ever feel stale.
         */
        val answerCache: Boolean = true,
        val nanokaHomeUrl: String = "https://hsr.nanoka.cc/",
        val nanokaCdnUrl: String = "https://static.nanoka.cc/hsr",
        val nanokaVersion: String? = null,
        /**
         * Source for the PT-BR core kit data ([com.cyrene.knowledge.StarRailStationIngestionSource]):
         * character profiles/skills/eidolons, light cones and relic sets in real Portuguese
         * (nanoka's text is English). The site is a JSON app; [srsDataUrl] is its data API and
         * [srsDeploymentId] (the patch-versioned deploy hash) is auto-discovered from [srsHomeUrl]'s
         * HTML — set it (blank/null = auto) to pin a patch. [srsLocale] is the path prefix the
         * data files live under. When starrailstation is unreachable the reindex falls back to
         * full (English) nanoka, so a bad fetch never leaves the KB empty.
         */
        val srsHomeUrl: String = "https://starrailstation.com/pt/",
        val srsDataUrl: String = "https://starrailstation.com/api/v1/datav2/",
        val srsLocale: String = "pt",
        val srsDeploymentId: String? = null,
        val batchSize: Int = 64,
        val topK: Int = 5,
        val similarityThreshold: Double = 0.55,
        val searxngUrl: String? = null,
        /**
         * When true, a web-sourced HSR answer is passed through a cheap grounding judge
         * (LLM verdict "sim/nao" against the fetched page text) before it's sent; an
         * unsupported answer is replaced by the abstain message. Local-KB answers skip this
         * (the base is authoritative). Adds one short LLM round-trip per WEB answer only —
         * turn off if web replies feel slow and you trust the deterministic grounding alone.
         */
        val verifyWebAnswers: Boolean = true,
        /**
         * How many of the top web-search results to open and read in full (download the
         * page, strip it to readable text) instead of trusting only the 1–2 sentence
         * SearXNG snippet. Full page text is what lets the brain reconstruct a complete
         * leaked/older kit that no snippet ever contains. 0 disables fetching (snippets
         * only). Kept low (2): wiki pages like game8 are huge and the budget is better
         * spent reading FEW pages DEEPLY (see [webFetchCharLimit]) than many shallowly —
         * snippets are still returned for ALL results, so source breadth isn't lost.
         */
        val webFetchPages: Int = 2,
        /**
         * Mihomo parsed-showcase endpoint for `/build` ({uid} appended). Public API from the
         * Mar-7th ecosystem; returns the player's showcased characters with full relic stats.
         */
        val mihomoUrl: String = "https://api.mihomo.me/sr_info_parsed",
        /**
         * StarRailScore weight table (same maintainer ecosystem as mihomo, ids match) used by
         * the deterministic relic scorer behind `/build`: per-character main-stat weights per
         * slot, substat weights, and the normalization constant.
         */
        val scoreJsonUrl: String = "https://raw.githubusercontent.com/Mar-7th/StarRailScore/master/score.json",
        /**
         * Sources for the `hsr_character` cache (names in en/pt/es + fribbels build
         * metadata), harvested by [com.cyrene.hsr.FribbelsHarvester] on a ~30-day cycle:
         * StarRailRes index (same ids as mihomo), the fribbels/hsr-optimizer git tree
         * listing (GitHub API, 1 call per harvest) and its raw-file base.
         */
        val starRailResBase: String = "https://raw.githubusercontent.com/Mar-7th/StarRailRes/master/index_min/",
        val fribbelsTreeUrl: String = "https://api.github.com/repos/fribbels/hsr-optimizer/git/trees/main?recursive=1",
        val fribbelsRawBase: String = "https://raw.githubusercontent.com/fribbels/hsr-optimizer/main/",
        /**
         * Per-page cap (characters) on the extracted text fed into the prompt, so one
         * sprawling wiki page can't swallow the context window. Deliberately large: on
         * pages like game8 the leading ~8k chars are table-of-contents / ratings / nav
         * noise and the real ability multipliers and traces don't appear until ~14–16k in,
         * so a small cap would clip exactly the kit numbers. With num-ctx 16384 the
         * 2×18000 worst case (~9k tokens of web text) still leaves room for the brain
         * prompt, the local-KB hit, and the [Performance.knowledgeNumPredict] reply budget.
         * Lower this (and/or [webFetchPages]) if you run a smaller num-ctx.
         */
        val webFetchCharLimit: Int = 18000,
    )
}
