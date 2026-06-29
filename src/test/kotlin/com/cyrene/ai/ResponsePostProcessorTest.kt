package com.cyrene.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the last line of defence before text reaches Discord: reasoning models' internal
 * `<think>…</think>` chatter must be stripped, never shown to users.
 */
class ResponsePostProcessorTest {

    private val processor = ResponsePostProcessor()

    @Test
    fun `strips a single inline think block`() {
        assertEquals("Olá!", processor.process("<think>raciocínio interno</think>Olá!"))
    }

    @Test
    fun `strips a multiline think block`() {
        val raw = "<think>\nlinha 1\nlinha 2\n</think>\nResposta final"
        assertEquals("Resposta final", processor.process(raw))
    }

    @Test
    fun `strips multiple think blocks`() {
        assertEquals("AB", processor.process("<think>x</think>A<think>y</think>B"))
    }

    @Test
    fun `leaves ordinary text untouched apart from trimming`() {
        assertEquals("texto normal", processor.process("  texto normal  "))
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals("", processor.process(""))
    }
}
