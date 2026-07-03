package com.cyrene.ai

import com.cyrene.config.BotProperties
import net.dv8tion.jda.api.entities.Message
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Service
import org.springframework.util.MimeType
import org.springframework.util.MimeTypeUtils
import java.util.concurrent.TimeUnit

/**
 * Extracts the content of an image attachment as text, so screenshots (an HSR build, a
 * relic, a battle result) can flow through the existing text-only pipeline: the extracted
 * text is appended to the user's turn and everything downstream — intent gate, grounding,
 * voice — stays unchanged.
 *
 * Fail-soft everywhere: disabled model, no image, oversized file, download or inference
 * failure all return null, and the caller replies text-only exactly as before. A broken
 * vision path must never take down a reply that would have worked without the image.
 */
@Service
class VisionService(
    private val chatModel: OllamaChatModel,
    private val properties: BotProperties,
    private val metrics: AiMetrics,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Extracted text of the first image attached to [message], or null (see class doc). */
    fun describeFirstImage(message: Message): String? {
        val model = properties.visionModelName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val image = message.attachments.firstOrNull { it.isImage } ?: return null
        if (image.size > MAX_IMAGE_BYTES) {
            log.debug("Skipping vision: attachment {} is {} bytes (cap {})", image.fileName, image.size, MAX_IMAGE_BYTES)
            return null
        }
        return try {
            val bytes = image.proxy.download().get(15, TimeUnit.SECONDS).use { it.readBytes() }
            val mime = image.contentType
                ?.let { runCatching { MimeType.valueOf(it) }.getOrNull() }
                ?: MimeTypeUtils.IMAGE_PNG
            val userMessage = UserMessage.builder()
                .text(VISION_PROMPT)
                .media(Media(mime, ByteArrayResource(bytes)))
                .build()
            val raw = metrics.timePass("vision") {
                chatModel.call(Prompt(listOf(userMessage), visionOptions(model))).result.output.text
            }?.trim().orEmpty()
            metrics.count("cyrene.vision", "result", if (raw.isBlank()) "empty" else "ok")
            raw.ifBlank { null }
        } catch (e: Exception) {
            metrics.count("cyrene.vision", "result", "error")
            log.warn("Vision extraction failed for attachment {}; replying text-only", image.fileName, e)
            null
        }
    }

    /**
     * Vision options: low temperature (transcription, not prose) and a modest context —
     * the prompt is short and image tokens are encoded separately, so the knowledge-path
     * 16k window would only waste KV-cache memory here.
     */
    private fun visionOptions(model: String): OllamaOptions =
        OllamaOptions.builder()
            .model(model)
            .temperature(0.1)
            .numCtx(4096)
            .numPredict(1024)
            .numThread(properties.performance.numThread)
            .build()

    companion object {
        /** Skip images above this size — Discord allows huge uploads, base64 inflates them further. */
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

        /**
         * Merges the extracted image text into the user's turn. Null/blank extraction leaves
         * the content untouched (the fail-soft contract); an image-only mention gets a default
         * question so the model has an instruction to answer. Pure, so listener behaviour is
         * unit-testable without JDA or a model.
         */
        fun augmentContent(content: String, imageText: String?): String {
            if (imageText.isNullOrBlank()) return content
            val base = content.ifBlank { "O que você vê nessa imagem?" }
            return "$base\n\n[Conteúdo da imagem anexada: $imageText]"
        }

        /**
         * Transcription directive. The output is consumed by the brain/voice passes as
         * source data, so it must be factual and complete — numbers matter more than prose.
         */
        val VISION_PROMPT = """
            Você extrai o conteúdo de imagens para outro assistente usar como contexto.
            Descreva o que a imagem mostra, em PT-BR, de forma factual e completa.

            Se for um screenshot de Honkai: Star Rail (personagem, build, relíquias, cone
            de luz, status, resultado de batalha), transcreva FIELMENTE todos os nomes,
            números, níveis, substatus e conjuntos visíveis — os números exatos importam.

            Não invente o que não está visível. Não dê opinião. Apenas o conteúdo.
        """.trimIndent()
    }
}
