package com.cyrene.ai

import com.cyrene.config.BotProperties
import com.cyrene.conversation.MentionMessage
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.stereotype.Service

/**
 * Generates short PT-BR personality summaries about Discord users.
 *
 * Two entry points:
 *  - [generateBaseline]: called the first time a user interacts with the bot. Produces a
 *    neutral one-line baseline from name + role since there is no message history yet.
 *  - [refreshFromExchanges]: called every N exchanges. Re-summarizes by feeding the prior
 *    summary plus the recent question/answer pairs to the brain model.
 *
 * Uses the raw [OllamaChatModel] (no tools, no Cyrene persona). The output is metadata
 * that flows into the voice pass as system context — it should NOT be in character.
 */
@Service
class PersonalitySummarizer(
    private val chatModel: OllamaChatModel,
    private val properties: BotProperties,
    private val postProcessor: ResponsePostProcessor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun generateBaseline(member: Member): String {
        val roleName = member.roles.maxByOrNull { it.position }?.name ?: "Sem cargo"
        return generateBaseline(member.effectiveName, "Cargo mais alto: $roleName.")
    }

    /**
     * Baseline used for DM sessions, where no [Member] / role exists. Same shape as the
     * guild path but without any role descriptor.
     */
    fun generateBaseline(user: User): String {
        return generateBaseline(user.effectiveName, "Contato em mensagem direta (DM).")
    }

    private fun generateBaseline(effectiveName: String, contextLine: String): String {
        val system = """
            Você gera um resumo de UMA frase em PT-BR sobre um usuário do Discord, a ser
            usado como contexto interno por outro bot. Seja neutro, factual, curto.
            NÃO use voz de personagem. NÃO use emojis. Máximo 1 frase, até 200 caracteres.
            Comece com "O usuário".
        """.trimIndent()
        val userMsg = "Nome: $effectiveName. $contextLine " +
            "Como ainda não há histórico de conversas, gere apenas uma frase introdutória neutra."
        return callModel(system, userMsg).ifBlank {
            "O usuário $effectiveName ainda não tem histórico de conversa registrado."
        }
    }

    fun refreshFromExchanges(existingSummary: String?, exchanges: List<MentionMessage>): String {
        if (exchanges.isEmpty()) return existingSummary.orEmpty()
        val system = """
            Você atualiza um resumo de personalidade de UM usuário do Discord, a ser usado
            como contexto interno por outro bot. Combine o resumo anterior (se houver) com
            os trechos recentes de conversa fornecidos. Mantenha tudo neutro, factual e
            curto. NÃO use voz de personagem. NÃO use emojis. Máximo 3 frases, até 500
            caracteres no total. Foco em: gostos, temas recorrentes, pedidos explícitos do
            tipo "não faça X". Cada frase deve começar com "O usuário".
        """.trimIndent()
        val transcript = exchanges.asReversed().joinToString("\n\n") { m ->
            "Pergunta: ${m.userMessage.take(500)}\nResposta: ${m.assistantReply.take(500)}"
        }
        val user = buildString {
            if (!existingSummary.isNullOrBlank()) {
                appendLine("Resumo anterior:")
                appendLine(existingSummary)
                appendLine()
            }
            appendLine("Trechos recentes:")
            append(transcript)
        }
        return callModel(system, user).ifBlank { existingSummary.orEmpty() }
    }

    private fun callModel(system: String, user: String): String {
        return try {
            val prompt = Prompt(
                listOf(SystemMessage(system), UserMessage(user)),
                OllamaOptions.builder()
                    .model(properties.brainModelName)
                    .numCtx(properties.performance.numCtx)
                    .numPredict(256)
                    .numThread(properties.performance.numThread)
                    .build(),
            )
            val raw = chatModel.call(prompt).result.output.text ?: ""
            postProcessor.process(raw).trim()
        } catch (e: Exception) {
            log.warn("PersonalitySummarizer call failed", e)
            ""
        }
    }
}
