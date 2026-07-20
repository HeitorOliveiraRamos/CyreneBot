package com.cyrene.ai

import com.cyrene.ai.OllamaAiService.Companion.commandHintFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [OllamaAiService.commandHintFor] short-circuits the whole pipeline: a hit means the user
 * gets a canned "use `/x`" reply and never reaches the model. That makes a false positive
 * expensive — it interrupts a joke or a real HSR question with a command list — so the
 * negative cases below matter more than the positive ones.
 */
class CommandHintTest {

    @Test
    fun `a prose moderation request names the command that replaces it`() {
        assertEquals("mutar", commandHintFor("muta o <@123> por 10 minutos"))
        assertEquals("mutar", commandHintFor("silencia esse cara aí"))
        assertEquals("banir", commandHintFor("bane o <@456> por toxicidade"))
        assertEquals("limpar", commandHintFor("limpa as mensagens do canal"))
        assertEquals("limpar", commandHintFor("apaga as últimas 50 mensagens"))
        assertEquals("cargo", commandHintFor("dá o cargo Membro pro <@111>"))
        assertEquals("cargo", commandHintFor("tira o cargo Mutado do <@222>"))
        assertEquals("criar-canal", commandHintFor("cria um canal chamado geral"))
        assertEquals("expulsar", commandHintFor("expulsa o <@789> do servidor"))
        assertEquals("modo-lento", commandHintFor("ativa o modo lento de 30 segundos"))
        assertEquals("modo-lento", commandHintFor("bota slowmode aí"))
        assertEquals("desmutar", commandHintFor("desmuta o <@333> por favor"))
        assertEquals("desbanir", commandHintFor("desbane o <@444>"))
        assertEquals("avisar", commandHintFor("avisa esse membro aí"))
        assertEquals("avisar", commandHintFor("dá uma advertência pro <@555>"))
    }

    @Test
    fun `the un- forms beat their base command, so desmutar never resolves to mutar`() {
        assertEquals("desmutar", commandHintFor("desmutar o <@1>"))
        assertEquals("desbanir", commandHintFor("desbanir o <@2>"))
    }

    @Test
    fun `the speaker tag the mention path prepends doesn't hide the request`() {
        assertEquals("mutar", commandHintFor("[Heitor]: muta o <@123>"))
    }

    @Test
    fun `object-anchored rules win over the purge rule, so a role request isn't read as a purge`() {
        // "tira" is in both vocabularies; the object decides.
        assertEquals("cargo", commandHintFor("tira o cargo do <@222>"))
    }

    @Test
    fun `everyday verbs without a server object stay conversation`() {
        // The whole reason the generic verbs need an object pairing.
        assertNull(commandHintFor("apaga essa imagem da minha mente kkk"))
        assertNull(commandHintFor("dá uma olhada nisso aqui"))
        assertNull(commandHintFor("cria uma história sobre a acheron"))
        assertNull(commandHintFor("tira a march do meu time"))
        // "lento" alone is a complaint about the bot, not a request to throttle the channel.
        assertNull(commandHintFor("nossa que lento hoje hein"))
        assertNull(commandHintFor("você tá lenta demais kkk"))
        // "avisa" is everyday speech until someone is actually named.
        assertNull(commandHintFor("me avisa quando o banner sair"))
        assertNull(commandHintFor("avisa aí se der ruim"))
    }

    @Test
    fun `banter aimed at the bot is never answered with a command list`() {
        // "cala a boca" is excluded from the vocabulary on purpose — answering it with a
        // timeout command is the joke-killing behaviour the intent gate was reworked to avoid.
        assertNull(commandHintFor("cala a boca kkkk"))
        assertNull(commandHintFor("oi, tudo bem?"))
        assertNull(commandHintFor("te amo cyrene"))
    }

    @Test
    fun `HSR questions are never mistaken for server actions`() {
        assertNull(commandHintFor("qual a build da acheron?"))
        assertNull(commandHintFor("qual o melhor cone pro welt?"))
        // "caminho" / "cargo"-adjacent game vocabulary must not trip the role rule.
        assertNull(commandHintFor("qual o caminho da firefly?"))
        assertNull(commandHintFor("quantos membros tem na facção Expresso Astral?"))
    }
}
