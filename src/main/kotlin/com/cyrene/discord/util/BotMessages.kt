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

    /** A ◀ ▶ page button clicked after its state expired (bot restart or LRU eviction). */
    const val PAGES_EXPIRED =
        "Essa resposta já é antiga e eu perdi as outras páginas dela 😔 Me pergunta de novo que eu refaço!"

    /** A reply pipeline failed unexpectedly. */
    const val ERROR =
        "Ai, me embolei toda aqui e não consegui responder agora 😔 Tenta de novo daqui a pouco?"

    /** `/build` without a linked UID. */
    const val BUILD_NO_UID =
        "Antes eu preciso saber quem você é no jogo! Vincula seu UID com `/uid uid:<seu UID>` e me chama de novo. 💜"

    /** `/build` where mihomo doesn't know the UID (wrong number or profile hidden). */
    fun buildUidNotFound(uid: String): String =
        "Não achei o UID **$uid** — confere se digitou certo e se o seu perfil está público nas " +
            "configurações do jogo (Privacidade → mostrar detalhes dos personagens)."

    /** `/build` for a character that isn't in the player's showcase. */
    fun buildNotInShowcase(query: String, available: List<String>): String {
        val list = available.filter { it.isNotBlank() }
        val suffix = if (list.isEmpty()) {
            "Sua vitrine está vazia — adiciona personagens nela dentro do jogo e tenta de novo."
        } else {
            "Na sua vitrine eu enxergo: ${list.joinToString(", ")}. Coloca a personagem na vitrine do jogo pra eu poder avaliar."
        }
        return "Não encontrei **$query** na sua vitrine 😔 $suffix"
    }

    /** `/build` for a character the community weight table doesn't cover yet. */
    fun buildNoWeights(name: String): String =
        "A **$name** é nova demais — a régua da comunidade ainda não tem pesos pra ela, e eu me " +
            "recuso a chutar nota 😌 Tenta de novo depois da próxima atualização da tabela."

    /**
     * Live pipeline status lines. Shown while a reply is being produced — the listener posts
     * the first one as a reply and edits it in place as stages advance, then deletes it when
     * the real answer lands. Operational copy, not persona output.
     */
    const val STATUS_KNOWLEDGE = "🔎 Deixa eu procurar na minha base de conhecimento…"
    const val STATUS_WEB = "🌐 Não tinha tudo na base, pesquisando na internet…"
    const val STATUS_MODERATION = "🛠️ Deixa comigo, já estou cuidando disso…"
    const val STATUS_WRITING = "✍️ Só um instante, organizando a resposta…"

    /**
     * HSR knowledge question where no real source (local base nor web) backed the answer.
     * Sent INSTEAD of letting the model invent — abstaining beats a confident wrong kit.
     * Static (no LLM call) so the abstain path stays fast.
     */
    fun knowledgeMiss(name: String?): String {
        val who = name?.trim()?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""
        return "Hmm, não achei nada confiável sobre isso na minha base nem na web$who 😔 " +
            "Pode ser que esse nome não exista ou esteja escrito diferente — me dá mais um detalhe?"
    }
}
