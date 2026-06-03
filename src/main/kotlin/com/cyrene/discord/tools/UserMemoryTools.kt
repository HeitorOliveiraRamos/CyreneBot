package com.cyrene.discord.tools

import com.cyrene.conversation.UserMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * Tool that lets the LLM persist a durable fact about the user it is talking to, so the bot
 * can recall it in future conversations.
 *
 * Privacy: the caller's identity comes from [DiscordToolContext] (never from the model's
 * arguments), and saving only happens when that user has opted into memory via `/memoria`.
 * When memory is off the tool saves nothing and returns a note the bot can relay, inviting
 * the user to enable it.
 */
@Component
class UserMemoryTools(
    private val userMemoryStore: UserMemoryStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(
        description = "Salva uma informação importante e duradoura sobre o usuário com quem você está " +
            "falando, para você lembrar nas próximas conversas. Use SEMPRE que ele compartilhar algo " +
            "que valha a pena guardar ou pedir explicitamente para você lembrar: preferências (como " +
            "quer ser chamado, gostos, o que não gosta), datas importantes (aniversário), fatos pessoais " +
            "relevantes, ou pedidos do tipo 'lembra que...', 'anota que...', 'não esqueça que...'. " +
            "Guarde UM fato conciso por chamada, em terceira pessoa (ex.: 'Prefere ser chamado de Léo'). " +
            "NÃO use para coisas triviais ou passageiras da conversa atual.",
    )
    fun rememberAboutUser(
        @ToolParam(description = "O fato a lembrar, conciso e em terceira pessoa. Ex.: 'É alérgico a amendoim'.")
        fact: String,
        toolContext: ToolContext,
    ): Map<String, Any?> {
        val ctx = ctx(toolContext)
        val saved = userMemoryStore.rememberFact(ctx.callerUserId, fact)
        return if (saved) {
            log.info("Saved user fact for caller={}", ctx.callerUserId)
            mapOf("ok" to true, "saved" to fact.trim())
        } else {
            mapOf(
                "ok" to false,
                "reason" to "memory_disabled",
                "note" to "O usuário não ativou a memória. Nada foi salvo. " +
                    "Convide-o a usar o comando /memoria para que você possa lembrar das coisas dele.",
            )
        }
    }

    private fun ctx(toolContext: ToolContext): DiscordToolContext {
        val raw = toolContext.context[DiscordToolContext.KEY]
        require(raw is DiscordToolContext) {
            "DiscordToolContext missing from ToolContext under key '${DiscordToolContext.KEY}'"
        }
        return raw
    }
}
