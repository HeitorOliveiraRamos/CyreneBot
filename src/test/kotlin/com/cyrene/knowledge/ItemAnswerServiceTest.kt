package com.cyrene.knowledge

import com.cyrene.hsr.ItemEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the pure core of the deterministic item path: which questions count as
 * effect-shaped ([ItemAnswerService.wantsItem]) and the render of an [ItemEffect]
 * row ([ItemAnswerService.rendered]).
 */
class ItemAnswerServiceTest {

    // -------------------- wantsItem (routing) -------------------- //

    @Test
    fun `effect vocabulary cues the item path`() {
        assertTrue(ItemAnswerService.wantsItem("qual o efeito do cone Along the Passing Shore?"))
        assertTrue(ItemAnswerService.wantsItem("qual a passiva do cone Incessant Rain?"))
        assertTrue(ItemAnswerService.wantsItem("bônus de 2 peças da Arena Rutilante"))
        assertTrue(ItemAnswerService.wantsItem("o que faz o ornamento Izumo?"))
    }

    @Test
    fun `bare faz does not cue, only the que-faz phrase`() {
        assertFalse(ItemAnswerService.wantsItem("o cone Incessant Rain faz sentido na Acheron?"))
        assertTrue(ItemAnswerService.wantsItem("o que faz o cone Incessant Rain?"))
    }

    @Test
    fun `comparison and judgment questions are not item-shaped`() {
        assertFalse(ItemAnswerService.wantsItem("qual cone é melhor pra Acheron?"))
        assertFalse(ItemAnswerService.wantsItem("vale a pena o set Poeta da Erradicação?"))
    }

    // -------------------- rendered -------------------- //

    @Test
    fun `render bolds the name and kind and keeps the effect lines`() {
        val item = ItemEffect(
            "Arena Rutilante", "Ornamento Planar (Planar Ornament)",
            listOf("Bônus 2 peças: Aumenta a Taxa de CRIT em 8%."),
        )
        assertEquals(
            "**Arena Rutilante — Ornamento Planar (Planar Ornament)**\n" +
                "Bônus 2 peças: Aumenta a Taxa de CRIT em 8%.",
            ItemAnswerService.rendered(item),
        )
    }

    @Test
    fun `an item with no effect lines renders just the bold header`() {
        assertEquals("**X — Cone de Luz (Light Cone)**", ItemAnswerService.rendered(ItemEffect("X", "Cone de Luz (Light Cone)", emptyList())))
    }
}
