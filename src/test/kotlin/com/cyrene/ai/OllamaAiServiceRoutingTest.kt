package com.cyrene.ai

import com.cyrene.ai.OllamaAiService.Intent
import com.cyrene.ai.OllamaAiService.VoicePath
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the two pure routing decisions extracted from [OllamaAiService]:
 * [OllamaAiService.parseIntent] (intent-gate output → routing class) and
 * [OllamaAiService.selectVoicePath] (brain result + intent → voice rendering). These are
 * the heart of the brain/voice pipeline and the part most prone to silent regressions, so
 * they're tested in isolation without invoking a model.
 */
class OllamaAiServiceRoutingTest {

    @Test
    fun `parseIntent maps the mod prefix to MODERATION, tolerating case and punctuation`() {
        assertEquals(Intent.MODERATION, OllamaAiService.parseIntent("mod"))
        assertEquals(Intent.MODERATION, OllamaAiService.parseIntent("MOD"))
        assertEquals(Intent.MODERATION, OllamaAiService.parseIntent("  mod.  "))
        assertEquals(Intent.MODERATION, OllamaAiService.parseIntent("moderation"))
    }

    @Test
    fun `parseIntent maps the kb prefix to KNOWLEDGE`() {
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.parseIntent("kb"))
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.parseIntent("KB"))
        assertEquals(Intent.KNOWLEDGE, OllamaAiService.parseIntent("kb\n"))
    }

    @Test
    fun `parseIntent maps the chat prefix to CHAT`() {
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("chat"))
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("Chat"))
    }

    @Test
    fun `parseIntent defaults blank or unrecognised output to CHAT (the safe fallback)`() {
        // CHAT can never misfire a moderation tool, so anything ambiguous must land here.
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent(""))
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("   "))
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("banana"))
        // Must START with the keyword — a sentence merely containing it is not a match.
        assertEquals(Intent.CHAT, OllamaAiService.parseIntent("acho que isso é moderation"))
    }

    @Test
    fun `selectVoicePath sends a real knowledge result to the full-detail KNOWLEDGE path`() {
        assertEquals(
            VoicePath.KNOWLEDGE,
            OllamaAiService.selectVoicePath("Acheron é do elemento Raio, caminho Nihility.", Intent.KNOWLEDGE),
        )
    }

    @Test
    fun `selectVoicePath sends a real moderation result to the terse FOCUSED path`() {
        assertEquals(
            VoicePath.FOCUSED,
            OllamaAiService.selectVoicePath("Timeout de 10 minutos aplicado em <@123>.", Intent.MODERATION),
        )
    }

    @Test
    fun `selectVoicePath treats sentinels and blank output as no-action under every intent`() {
        for (intent in Intent.entries) {
            assertEquals(VoicePath.CONVERSATIONAL, OllamaAiService.selectVoicePath("Sem ação necessária.", intent))
            assertEquals(VoicePath.CONVERSATIONAL, OllamaAiService.selectVoicePath("Pronto.", intent))
            assertEquals(VoicePath.CONVERSATIONAL, OllamaAiService.selectVoicePath("   ", intent))
        }
    }

    @Test
    fun `selectVoicePath sentinel matching ignores case and surrounding whitespace`() {
        assertEquals(
            VoicePath.CONVERSATIONAL,
            OllamaAiService.selectVoicePath("  sem AÇÃO necessária.  ", Intent.KNOWLEDGE),
        )
    }

    @Test
    fun `selectVoicePath narrates a real result tersely when the intent is not KNOWLEDGE`() {
        assertEquals(VoicePath.FOCUSED, OllamaAiService.selectVoicePath("Algum resultado real.", Intent.CHAT))
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
