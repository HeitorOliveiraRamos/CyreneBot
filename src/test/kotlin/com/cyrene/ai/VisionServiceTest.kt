package com.cyrene.ai

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.util.MimeType
import org.springframework.util.MimeTypeUtils

/**
 * Guards the format normalisation that lets non-native uploads (webp) reach Ollama, whose
 * decoder only reads png/jpeg/gif. augmentContent's fail-soft merge is covered here too.
 */
class VisionServiceTest {

    private fun pngBytes(): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun `png passes through untouched`() {
        val bytes = pngBytes()
        val (mime, out) = VisionService.toOllamaImage(MimeTypeUtils.IMAGE_PNG, bytes)
        assertEquals(MimeTypeUtils.IMAGE_PNG, mime)
        assertSame(bytes, out) // no re-encode for native formats
    }

    @Test
    fun `non-native mime is transcoded to png`() {
        // Bytes are really png, but declared as webp — the branch must decode and re-encode.
        val (mime, out) = VisionService.toOllamaImage(MimeType.valueOf("image/webp"), pngBytes())
        assertEquals(MimeTypeUtils.IMAGE_PNG, mime)
        assertTrue(out.isNotEmpty() && ImageIO.read(out.inputStream()) != null)
    }

    @Test
    fun `undecodable bytes fall through unchanged`() {
        val junk = byteArrayOf(1, 2, 3)
        val (mime, out) = VisionService.toOllamaImage(MimeType.valueOf("image/webp"), junk)
        assertEquals("webp", mime.subtype) // fail-soft: caller sends original, outer catch handles reject
        assertSame(junk, out)
    }

    @Test
    fun `augmentContent gives an image-only mention a default question`() {
        assertEquals(
            "O que você vê nessa imagem?\n\n[Conteúdo da imagem anexada: um gato]",
            VisionService.augmentContent("", "um gato"),
        )
    }

    @Test
    fun `augmentContent leaves content untouched when extraction is blank`() {
        assertEquals("oi", VisionService.augmentContent("oi", null))
    }
}
