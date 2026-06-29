package com.cyrene.discord.util

/**
 * User-facing fallback copy in Cyrene's voice, centralized so the bot's out-of-band replies
 * (rate limit, busy, unexpected errors) stay consistent and in character instead of reading
 * like a stack trace.
 *
 * These are NOT the persona file (which must not be edited) — they're the hardcoded
 * operational strings the listeners send when there is no model output to render, e.g. when
 * the pipeline failed or was rejected before it ever reached Ollama.
 */
object BotMessages {

    /** Per-user reply cooldown hit. */
    fun cooldown(waitSeconds: Long): String =
        "Calma, uma de cada vez 💜 Espera só ${waitSeconds}s e me chama de novo."

    /** Inference gate full, on the mention path (so we can address the user by name). */
    fun busy(name: String): String =
        "Tô ocupada com outra conversa agora, $name… me dá uns segundos e me chama de novo. 💭"

    /** Inference gate full, inside an active session. */
    const val BUSY_SESSION =
        "Só um instante, ainda estou respondendo outra coisa… manda de novo daqui a pouco. 💭"

    /** A reply pipeline failed unexpectedly. */
    const val ERROR =
        "Ai, me embolei toda aqui e não consegui responder agora 😔 Tenta de novo daqui a pouco?"
}
