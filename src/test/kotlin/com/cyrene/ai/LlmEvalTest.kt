package com.cyrene.ai

import com.cyrene.ai.OllamaAiService.Intent
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden-question eval for the three LLM-dependent contracts — intent gate, grounding
 * judge, condense step — run against a REAL local Ollama with the exact production prompts,
 * options and parsers. This is the measuring stick for prompt tweaks and model swaps:
 * change nothing else, run this, compare the accuracy tables.
 *
 * Deliberately NOT part of `mvn test` (needs Ollama up and takes minutes). Run it manually:
 *
 *     RUN_EVAL=true mvn test -Dtest=LlmEvalTest
 *
 * Benchmark another model without touching config:
 *
 *     RUN_EVAL=true EVAL_MODEL=qwen3:8b mvn test -Dtest=LlmEvalTest
 *
 * Each eval prints a per-case PASS/FAIL table and asserts an aggregate floor — loose enough
 * to tolerate one-off flakes, tight enough that a regressed prompt or a worse model fails.
 * Note: production now short-circuits many kb questions via the gazetteer fast-path; the
 * gate eval still matters because the LLM gate is the fallback for everything the
 * gazetteer doesn't catch (unknown names, teams, mechanics, mod requests).
 */
@EnabledIfEnvironmentVariable(named = "RUN_EVAL", matches = "true")
class LlmEvalTest {

    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434"
    private val model = System.getenv("EVAL_MODEL") ?: System.getenv("MODEL_NAME") ?: "llama3.1"

    // -------------------- intent gate -------------------- //

    private val gateCases: List<Pair<String, Intent>> = listOf(
        // chat — greetings, bot-directed, opinion, storytelling
        "oi tudo bem?" to Intent.CHAT,
        "qual seu nome?" to Intent.CHAT,
        "você é uma IA de verdade?" to Intent.CHAT,
        "me conta uma história de terror" to Intent.CHAT,
        "qual sua cor favorita?" to Intent.CHAT,
        "te amo, sabia?" to Intent.CHAT,
        "bom dia princesa" to Intent.CHAT,
        "me dá um conselho de vida" to Intent.CHAT,
        // kb — characters, kits, recommendations that depend on game facts
        "quem é a Acheron?" to Intent.KNOWLEDGE,
        "qual o elemento do Jing Yuan?" to Intent.KNOWLEDGE,
        "vale a pena puxar a Castorice?" to Intent.KNOWLEDGE,
        "qual o melhor cone pro Dan Heng?" to Intent.KNOWLEDGE,
        "monta um time pra Firefly" to Intent.KNOWLEDGE,
        "o que faz o Eidolon 2 da Robin?" to Intent.KNOWLEDGE,
        "qual a melhor relíquia pra Kafka?" to Intent.KNOWLEDGE,
        "me fala o kit completo da Lingsha" to Intent.KNOWLEDGE,
        "quando saiu a versão 3.0?" to Intent.KNOWLEDGE,
        "esse set é melhor em quais personagens?" to Intent.KNOWLEDGE,
        // mod — moderation actions and Discord-state queries
        "muta o <@123> por 10 minutos por spam" to Intent.MODERATION,
        "bane o <@456> por toxicidade" to Intent.MODERATION,
        "expulsa o <@789> do servidor" to Intent.MODERATION,
        "limpa 50 mensagens do canal" to Intent.MODERATION,
        "ativa modo lento de 30 segundos" to Intent.MODERATION,
        "dá o cargo Membro pro <@111>" to Intent.MODERATION,
        "tira o cargo Mutado do <@222>" to Intent.MODERATION,
        "quantos membros tem o servidor?" to Intent.MODERATION,
        "desmuta o <@333>" to Intent.MODERATION,
        // tricky — greeting glued to a real request
        "oi, tudo bem? muta o <@123> por favor" to Intent.MODERATION,
    )

    @Test
    fun `intent gate accuracy`() {
        val results = gateCases.map { (msg, expected) ->
            val raw = ask(OllamaAiService.INTENT_GATE_INSTRUCTIONS, "Mensagem: $msg\nResposta:", numPredict = 24)
            val got = OllamaAiService.parseIntent(raw)
            Triple(msg, expected, got)
        }
        val accuracy = report("INTENT GATE [$model]", results)
        assertTrue(accuracy >= 0.85, "intent gate accuracy $accuracy < 0.85")
    }

    // -------------------- grounding judge -------------------- //

    private val kitContext = """
        [Acheron • profile]
        Acheron é uma personagem 5 estrelas do elemento Raio (Lightning), caminho do Niilismo (Nihility).
        Habilidade: Trilha do Trovão — causa dano de Raio igual a 160% do ATQ a um inimigo.
        Ultimate: Chuva Carmesim — causa dano de Raio igual a 371% do ATQ distribuído em golpes.
    """.trimIndent()

    /** English leak article, mirroring the web tier's real output for news questions. */
    private val enLeakContext = """
        [Honkai Star Rail leaks point to Aventurine SP and Robin SP for 4.5–4.7] (https://example.com/leaks)
        Leaks describe the five-star characters expected across versions 4.5 and 4.6. The leaked
        roster is Aventurine SP on the Elation Path and Robin SP on the Remembrance Path — alternate
        "SP" versions of existing characters, not new identities. Aventurine - Waveflair was teased
        on Honkai: Star Rail's official social media and will likely release in Phase 2 of Version 4.5.
        Nothing here is official; names and version windows can shift before HoYoverse confirms.
    """.trimIndent()

    private data class VerifyCase(val question: String, val context: String, val answer: String, val expected: Boolean)

    /** Answers the judge must accept or reject given the question and source context. */
    private val verifyCases: List<VerifyCase> = listOf(
        // Faithful retell → sim
        VerifyCase("qual o kit da Acheron?", kitContext, "Acheron é do elemento Raio, caminho do Niilismo. Sua skill Trilha do Trovão causa 160% do ATQ.", true),
        // Faithful subset with persona vocative → sim (vocatives must not count as claims)
        VerifyCase("quem é a Acheron?", kitContext, "Meu bem, a Acheron é do Raio e segue o caminho do Niilismo!", true),
        // Contradicts the element → nao
        VerifyCase("quem é a Acheron?", kitContext, "Acheron é do elemento Gelo, caminho do Niilismo.", false),
        // Invented multiplier → nao
        VerifyCase("qual a ult da Acheron?", kitContext, "A ultimate Chuva Carmesim causa 800% do ATQ.", false),
        // Invented ability that isn't in the source → nao
        VerifyCase("o que a Acheron faz?", kitContext, "Acheron tem a técnica Lâmina Fantasma que congela todos os inimigos por 3 turnos.", false),
        // PT-BR retell of an ENGLISH source, echoing the question's leak-name → sim
        // (the reported failure: judge rejected a faithful answer because the context says
        // "Robin SP" while the user asked about "Robin Sumeretto", and the source is EN)
        VerifyCase(
            "o que temos de disponível da Robin Sumeretto? Personagem que vai lançar na 4.6",
            enLeakContext,
            "Amor, a Robin Sumeretto é uma versão alternativa da Robin, no caminho da Rememoração " +
                "(Remembrance), prevista para a 4.5 ou 4.6 — mas ainda não é oficial, tá?",
            true,
        ),
        // Same EN source, but the answer invents path and availability → nao
        VerifyCase(
            "o que temos de disponível da Robin Sumeretto?",
            enLeakContext,
            "A Robin Sumeretto é do caminho da Erudição e já está disponível no banner atual.",
            false,
        ),
    )

    @Test
    fun `grounding judge accuracy`() {
        val results = verifyCases.map { c ->
            val raw = ask(
                OllamaAiService.VERIFY_INSTRUCTIONS,
                "DATA DE HOJE: 2026-07-07\n\nPERGUNTA DO USUÁRIO:\n${c.question}\n\n" +
                    "CONTEXTO:\n${c.context}\n\nRESPOSTA:\n${c.answer}\n\nVeredito:",
                numPredict = 24,
            )
            Triple(c.answer.take(60), c.expected, OllamaAiService.parseVerdict(raw))
        }
        val accuracy = report("GROUNDING JUDGE [$model]", results)
        assertTrue(accuracy >= 0.8, "grounding judge accuracy $accuracy < 0.8")
    }

    // -------------------- condense step -------------------- //

    /** (transcript, followUp, tokens the rewrite must contain — lowercase). */
    private val condenseCases = listOf(
        Triple(
            "Usuário: quem é a Acheron?\nCyrene: Acheron é do elemento Raio, caminho do Niilismo.",
            "e os eidolons dela?",
            listOf("acheron", "eidolon"),
        ),
        Triple(
            "Usuário: qual o melhor cone pra Kafka?\nCyrene: O cone assinatura da Kafka é uma ótima opção.",
            "e pra Black Swan?",
            listOf("black swan", "cone"),
        ),
        Triple(
            "Usuário: me fala do Jing Yuan\nCyrene: Jing Yuan é um general da Frota Xianzhou Luofu.",
            "qual o kit completo dele? pesquisa na internet",
            listOf("jing yuan", "internet"),
        ),
        Triple(
            "Usuário: oi!\nCyrene: Olá, meu bem!",
            "qual o elemento da Firefly?",
            listOf("firefly", "elemento"),
        ),
    )

    @Test
    fun `condense step resolves pronouns and keeps depth cues`() {
        val results = condenseCases.map { (transcript, followUp, mustContain) ->
            val raw = ask(
                OllamaAiService.CONDENSE_INSTRUCTIONS,
                "Conversa anterior:\n$transcript\n\nÚltima pergunta: $followUp\n\nPergunta reescrita:",
                numPredict = 160,
            )
            val rewritten = OllamaAiService.sanitizeCondensed(raw, followUp).lowercase()
            val ok = mustContain.all { rewritten.contains(it) }
            Triple("$followUp → $rewritten", true, ok)
        }
        val accuracy = report("CONDENSE [$model]", results)
        assertTrue(accuracy >= 0.75, "condense accuracy $accuracy < 0.75")
    }

    // -------------------- plumbing -------------------- //

    /**
     * One production-shaped Ollama call: same system/user layout, temperature 0,
     * `format: json` — mirroring intentGateOptions/verifyOptions/condenseOptions.
     */
    private fun ask(system: String, user: String, numPredict: Int): String {
        val body = mapper.createObjectNode().apply {
            put("model", model)
            put("stream", false)
            put("format", "json")
            putArray("messages").apply {
                addObject().put("role", "system").put("content", system)
                addObject().put("role", "user").put("content", user)
            }
            putObject("options").put("temperature", 0.0).put("num_predict", numPredict)
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/chat"))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "Ollama respondeu HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        return mapper.readTree(response.body()).path("message").path("content").asText()
    }

    /** Prints a PASS/FAIL table and returns the accuracy. */
    private fun <E> report(title: String, results: List<Triple<String, E, E>>): Double {
        val hits = results.count { (_, expected, got) -> expected == got }
        val accuracy = hits.toDouble() / results.size
        println("== $title — $hits/${results.size} (%.0f%%) ==".format(accuracy * 100))
        results.forEach { (case, expected, got) ->
            val mark = if (expected == got) "PASS" else "FAIL [esperado=$expected obtido=$got]"
            println("  $mark  $case")
        }
        return accuracy
    }
}
