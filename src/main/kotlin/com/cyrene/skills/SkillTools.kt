package com.cyrene.skills

import com.cyrene.config.BotProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Progressive-disclosure skills: step-by-step workflows stored as markdown files in
 * [BotProperties.skillsDir], one file per skill. Only each skill's name + one-line
 * description enters the brain prompt (see [catalogPrompt]); the full instructions are
 * loaded into context on demand when the model calls [loadSkill] — so a growing skill
 * library costs a couple of catalog lines per brain call, not its whole text.
 *
 * File convention (see skills/limpeza-de-spam.md):
 *   - file name (minus .md) = skill name the model uses with `loadSkill`;
 *   - first non-blank, non-heading line = the description shown in the catalog;
 *   - the whole file = the instructions the model follows after loading.
 *
 * The directory is re-scanned on every call — skills are meant to be edited while the
 * bot runs (fix the file, mention the bot again), and reading a handful of small files
 * per brain call is free next to an LLM round-trip. No directory = no skills = the
 * brain prompt and intent gate are byte-identical to before this class existed.
 */
@Component
class SkillTools(private val properties: BotProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class Skill(val name: String, val description: String, val body: String)

    fun skills(): List<Skill> {
        val dir = Path.of(properties.skillsDir)
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            dir.listDirectoryEntries()
                .filter { it.extension == "md" }
                .sortedBy { it.nameWithoutExtension }
                .map { parse(it.nameWithoutExtension, it.readText()) }
        } catch (e: Exception) {
            log.warn("Failed to scan skills dir '{}': {}", dir, e.message)
            emptyList()
        }
    }

    /**
     * Catalog block appended to the brain-pass system prompt: names + descriptions only,
     * plus the instruction to load-then-follow. Null when there are no skills.
     */
    fun catalogPrompt(): String? {
        val skills = skills().ifEmpty { return null }
        return buildString {
            appendLine("## Skills disponíveis (workflows passo-a-passo)")
            appendLine()
            appendLine(
                "Além das ferramentas acima, existem skills — procedimentos prontos. Quando o " +
                    "pedido do usuário corresponder a uma skill abaixo, chame `loadSkill` com o " +
                    "nome dela e SIGA os passos retornados, na ordem, usando as ferramentas normais.",
            )
            appendLine()
            skills.forEach { appendLine("- ${it.name}: ${it.description}") }
        }.trim()
    }

    /**
     * Suffix for the intent gate: skill-shaped requests must route to the tool-aware brain
     * (the only pass that can call `loadSkill`), so they are classified as "mod". Null when
     * there are no skills, keeping the gate prompt untouched.
     */
    fun gateSuffix(): String? {
        val skills = skills().ifEmpty { return null }
        return buildString {
            appendLine("Responda \"mod\" TAMBÉM quando a mensagem pedir uma destas tarefas:")
            skills.forEach { appendLine("- ${it.name}: ${it.description}") }
        }.trim()
    }

    @Tool(
        description = "Carrega as instruções completas de uma skill da lista 'Skills disponíveis'. " +
            "Chame quando o pedido do usuário corresponder a uma skill, ANTES de agir; depois siga " +
            "os passos retornados na ordem, usando as outras ferramentas.",
    )
    fun loadSkill(
        @ToolParam(description = "Nome exato da skill, como aparece na lista. Ex.: 'limpeza-de-spam'.")
        name: String,
    ): Map<String, Any?> {
        val all = skills()
        val skill = all.find { it.name.equals(name.trim(), ignoreCase = true) }
            ?: return mapOf(
                "found" to false,
                "note" to "Skill desconhecida. Disponíveis: ${all.joinToString { it.name }}",
            )
        log.debug("loadSkill: '{}' loaded ({} chars)", skill.name, skill.body.length)
        return mapOf("found" to true, "instructions" to skill.body)
    }

    internal companion object {
        /**
         * Pure parse of one skill file: description = first non-blank line that isn't a
         * markdown heading (falls back to the first heading text, then the name), capped so
         * a prose-first file can't bloat the catalog. The full text is the body.
         */
        internal fun parse(name: String, content: String): Skill {
            val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            val description = (
                lines.firstOrNull { !it.startsWith("#") }
                    ?: lines.firstOrNull()?.trimStart('#', ' ')
                    ?: name
                ).take(200)
            return Skill(name, description, content.trim())
        }
    }
}
