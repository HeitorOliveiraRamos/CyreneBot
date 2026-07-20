package com.cyrene.ai

import com.cyrene.ai.OllamaAiService.Intent
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the pure parsing decisions extracted from [OllamaAiService] — the
 * intent-gate output, the grounding verdict, and the fast-path heuristics in front of the
 * gate. These decide what pipeline a message takes, so they're tested in isolation without
 * invoking a model.
 */
class OllamaAiServiceRoutingTest {

    @Test
    fun `parseIntent maps a leftover mod verdict to CHAT, since moderation is commands-only now`() {
        // A model still primed on the old three-way prompt must not land anywhere special.
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("mod"))
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("""{"intent": "mod"}"""))
    }

    @Test
    fun `parseIntent maps the kb prefix to KNOWLEDGE`() {
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.parseIntent("kb"))
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.parseIntent("KB"))
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.parseIntent("kb\n"))
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.parseIntent("  kb.  "))
    }

    @Test
    fun `parseIntent maps the chat prefix to CHAT`() {
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("chat"))
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("Chat"))
    }

    @Test
    fun `parseIntent reads the JSON form produced under format=json`() {
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.parseIntent("""{"intent":"kb"}"""))
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("""{"intent": "chat"}"""))
        // Malformed / truncated JSON degrades to the bare-word fallback → safe CHAT default.
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("""{"intent": "k"""))
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("""{"outro_campo": "kb"}"""))
    }

    @Test
    fun `parseIntent defaults blank or unrecognised output to CHAT (the safe fallback)`() {
        // A wrongly-chatty reply is a far cheaper miss than a confidently invented kit.
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent(""))
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("   "))
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("banana"))
        // Must START with the keyword — a sentence merely containing it is not a match.
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("acho que isso é kb"))
    }

    @Test
    fun `hasCjk flags the Chinese-drift answer and passes clean PT-BR`() {
        // The reported failure: a PT-BR reply whose kit descriptions came out in Chinese.
        assertEquals(true, OllamaAiService.hasCjk("Deve ser usado para atacar um alvo单一目标。造成Aventurine防御力140%的想象伤害。"))
        assertEquals(true, OllamaAiService.hasCjk("提供全体友方一个护盾"))
        // Clean PT-BR with Latin-script proper nouns, numbers and emoji must pass.
        assertEquals(false, OllamaAiService.hasCjk("Aventurine é 5★, caminho da Preservação, elemento Imaginário! 💜"))
        assertEquals(false, OllamaAiService.hasCjk("**Straight Bet (Ataque Básico)**: causa 140% da DEF como dano Imaginário."))
        assertEquals(false, OllamaAiService.hasCjk(""))
    }

    @Test
    fun `parseVerdict fails only on a clear negative, passing everything else (fail-open)`() {
        // A clear "no" suppresses the answer (abstain)...
        assertEquals(false, OllamaAiService.parseVerdict("nao"))
        assertEquals(false, OllamaAiService.parseVerdict("Não."))
        assertEquals(false, OllamaAiService.parseVerdict("  no  "))
        assertEquals(false, OllamaAiService.parseVerdict("\"nao\""))
        // ...everything else passes, so an ambiguous/garbled judge can't silence a grounded reply.
        assertEquals(true, OllamaAiService.parseVerdict("sim"))
        assertEquals(true, OllamaAiService.parseVerdict("SIM"))
        assertEquals(true, OllamaAiService.parseVerdict(""))
        assertEquals(true, OllamaAiService.parseVerdict("talvez"))
    }

    @Test
    fun `parseVerdict reads the JSON form produced under format=json`() {
        assertEquals(false, OllamaAiService.parseVerdict("""{"veredito": "nao"}"""))
        assertEquals(false, OllamaAiService.parseVerdict("""{"veredito":"não"}"""))
        assertEquals(true, OllamaAiService.parseVerdict("""{"veredito": "sim"}"""))
        // Malformed JSON or a missing field falls back on the raw text → fail-open (pass).
        assertEquals(true, OllamaAiService.parseVerdict("""{"veredito": "na"""))
        assertEquals(true, OllamaAiService.parseVerdict("""{"outro": "nao"}"""))
    }

    @Test
    fun `fastPathIntent short-circuits obvious greetings and thanks to CHAT`() {
        for (msg in listOf("oi", "Oi", "OLÁ", "bom dia", "boa noite!", "obrigado.", "valeu!!!", "tudo bem?", "kkk")) {
            assertEquals(Intent.CHAT, OllamaAiService.fastPathIntent(msg), "expected fast-path CHAT for '$msg'")
        }
    }

    @Test
    fun `fastPathIntent strips a leading speaker tag before matching`() {
        assertEquals(Intent.CHAT, OllamaAiService.fastPathIntent("[Heitor]: oi"))
        assertEquals(Intent.CHAT, OllamaAiService.fastPathIntent("[Ana Maria]: boa noite"))
    }

    @Test
    fun `fastPathIntent treats blank input as CHAT`() {
        assertEquals(Intent.CHAT, OllamaAiService.fastPathIntent("   "))
        assertEquals(Intent.CHAT, OllamaAiService.fastPathIntent("[Heitor]:   "))
    }

    @Test
    fun `sanitizeCondensed accepts a clean one-line rewrite, stripping quotes and whitespace`() {
        assertEquals(
            "Quais são os Eidolons da Acheron?",
            OllamaAiService.sanitizeCondensed("  \"Quais são os Eidolons da Acheron?\"  ", "e os dela?"),
        )
    }

    @Test
    fun `gazetteerFastPath routes a mechanics-cue character mention to KNOWLEDGE`() {
        val knowsAcheron = { s: String -> s.contains("acheron", ignoreCase = true) }
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.gazetteerFastPath("me fala o kit da acheron", knowsAcheron))
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.gazetteerFastPath("build da Acheron", knowsAcheron))
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.gazetteerFastPath("[Heitor]: qual o elemento da Acheron", knowsAcheron))
    }

    @Test
    fun `gazetteerFastPath defers banter naming a character even when question-shaped`() {
        val knowsAcheron = { s: String -> s.contains("acheron", ignoreCase = true) }
        // A '?' or a generic question word is NOT a mechanics cue: these must reach the
        // LLM gate (which sees context and can keep the joke in chat), never fast-path
        // into a build dump — the "joke killed by relic stats" regression.
        assertEquals(null, OllamaAiService.gazetteerFastPath("será que a Acheron me ama?", knowsAcheron))
        assertEquals(null, OllamaAiService.gazetteerFastPath("a acheron é linda?", knowsAcheron))
        assertEquals(null, OllamaAiService.gazetteerFastPath("quem é a Acheron?", knowsAcheron))
        assertEquals(null, OllamaAiService.gazetteerFastPath("como a acheron pagaria boleto kkk?", knowsAcheron))
    }

    @Test
    fun `gazetteerFastPath defers a server-flavoured message with no mechanics cue`() {
        val knowsAcheron = { s: String -> s.contains("acheron", ignoreCase = true) }
        // Moderation is slash-commands only, so these carry no special routing weight — but
        // they still have no mechanics cue, so they defer to the LLM gate rather than being
        // fast-pathed into a kit dump.
        assertEquals(null, OllamaAiService.gazetteerFastPath("muta quem falou mal da acheron?", knowsAcheron))
        assertEquals(null, OllamaAiService.gazetteerFastPath("<@123> perguntou da acheron?", knowsAcheron))
        assertEquals(null, OllamaAiService.gazetteerFastPath("da o cargo acheron pro pessoal?", knowsAcheron))
    }

    @Test
    fun `gazetteerFastPath defers when the mention is not question-shaped or names nobody`() {
        val knowsAcheron = { s: String -> s.contains("acheron", ignoreCase = true) }
        // Casual chat naming a character must NOT fast-path (no '?' and no knowledge cue).
        assertEquals(null, OllamaAiService.gazetteerFastPath("a acheron é linda demais", knowsAcheron))
        // Question-shaped but no known character → LLM gate decides.
        assertEquals(null, OllamaAiService.gazetteerFastPath("qual seu nome?", { false }))
    }

    @Test
    fun `gateUserBlock includes up to two prior turns as labelled context`() {
        fun msg(role: MessageRole, content: String) = ConversationMessage(0L, role, content)
        val history = listOf(
            msg(MessageRole.USER, "imagina a acheron no pix"),
            msg(MessageRole.ASSISTANT, "kkk ela ia cobrar juros"),
            msg(MessageRole.USER, "e o welt entao?"),
        )
        val block = OllamaAiService.gateUserBlock(history, "e o welt entao?")
        assertEquals(
            """
            Conversa anterior (apenas contexto — classifique SÓ a última mensagem):
            Usuário: imagina a acheron no pix
            Bot: kkk ela ia cobrar juros

            Mensagem: e o welt entao?
            Resposta:
            """.trimIndent(),
            block,
        )
    }

    @Test
    fun `gateUserBlock degrades to the bare message when there is no prior turn`() {
        val history = listOf(ConversationMessage(0L, MessageRole.USER, "quem é a Acheron?"))
        assertEquals(
            "Mensagem: quem é a Acheron?\nResposta:",
            OllamaAiService.gateUserBlock(history, "quem é a Acheron?"),
        )
        assertEquals("Mensagem: oi\nResposta:", OllamaAiService.gateUserBlock(emptyList(), "oi"))
    }

    @Test
    fun `sanitizeCondensed reads the JSON form produced under format=json`() {
        assertEquals(
            "Quais são os Eidolons da Acheron?",
            OllamaAiService.sanitizeCondensed("""{"pergunta": "Quais são os Eidolons da Acheron?"}""", "e os dela?"),
        )
        // Truncated JSON falls back on the raw text, which fails the one-line checks → original question.
        assertEquals("e os dela?", OllamaAiService.sanitizeCondensed("""{"pergunta": "Quais""", "e os dela?"))
    }

    @Test
    fun `sanitizeCondensed falls back to the original question on blank, multi-line or bloated output`() {
        val original = "e os Eidolons dela?"
        // Blank → the model produced nothing usable.
        assertEquals(original, OllamaAiService.sanitizeCondensed("   ", original))
        // Multi-line → the model answered/explained instead of rewriting.
        assertEquals(original, OllamaAiService.sanitizeCondensed("Pergunta:\nQuais os Eidolons?", original))
        // Bloated → almost certainly an answer, not a question.
        assertEquals(original, OllamaAiService.sanitizeCondensed("x".repeat(301), original))
    }

    @Test
    fun `sanitizeGreeting unquotes one line and rejects run-ons and structure`() {
        // The happy path: one quoted line, as the models actually emit it.
        assertEquals(
            "Fui lá buscar pra você, amor. Olha só:",
            OllamaAiService.sanitizeGreeting("\"Fui lá buscar pra você, amor. Olha só:\"\n"),
        )
        // Observed failure: opener runs into an invented answer on the SAME line → too long.
        assertEquals(null, OllamaAiService.sanitizeGreeting("Anotado, amor: a composição é ${"x".repeat(160)}"))
        // Blank and list/heading-shaped output → canned fallback.
        assertEquals(null, OllamaAiService.sanitizeGreeting("   \n  "))
        assertEquals(null, OllamaAiService.sanitizeGreeting("- Relíquia A\n- Relíquia B"))
    }

    @Test
    fun `fastPathIntent returns null (defer to the LLM gate) for anything non-trivial`() {
        // Crucially, a moderation or HSR request must NEVER be fast-pathed.
        for (msg in listOf(
            "muta o <@123> por 10 minutos",
            "bane o fulano",
            "quem é a Acheron?",
            "oi, muta o fulano",   // greeting glued to a real request
            "valeu mano",          // not an exact whitelist match
            "me conta uma história",
        )) {
            assertEquals(null, OllamaAiService.fastPathIntent(msg), "'$msg' must defer to the LLM gate")
        }
    }
}
