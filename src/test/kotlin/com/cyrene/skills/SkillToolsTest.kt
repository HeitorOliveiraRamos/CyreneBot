package com.cyrene.skills

import com.cyrene.config.BotProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class SkillToolsTest {

    @TempDir
    lateinit var dir: Path

    private fun tools(skillsDir: String = dir.toString()) = SkillTools(
        BotProperties(token = "t", modelName = "m", skillsDir = skillsDir),
    )

    @Test
    fun `parse takes first non-heading line as description and whole file as body`() {
        val skill = SkillTools.parse("limpeza", "# Limpeza\n\nApaga spam do canal.\n\n## Passos\n1. x\n")
        assertThat(skill.name).isEqualTo("limpeza")
        assertThat(skill.description).isEqualTo("Apaga spam do canal.")
        assertThat(skill.body).contains("## Passos")
    }

    @Test
    fun `parse falls back to heading then name when file has no prose`() {
        assertThat(SkillTools.parse("s", "# Só Título").description).isEqualTo("Só Título")
        assertThat(SkillTools.parse("s", "").description).isEqualTo("s")
    }

    @Test
    fun `missing dir means no skills and null prompts`() {
        val t = tools(skillsDir = dir.resolve("nope").toString())
        assertThat(t.skills()).isEmpty()
        assertThat(t.catalogPrompt()).isNull()
        assertThat(t.gateSuffix()).isNull()
    }

    @Test
    fun `catalog lists name and description only, loadSkill returns full body`() {
        dir.resolve("limpeza-de-spam.md").writeText("# Limpeza\n\nApaga spam.\n\n## Passos\n1. purgeMessages\n")
        val t = tools()

        assertThat(t.catalogPrompt()).contains("- limpeza-de-spam: Apaga spam.").doesNotContain("purgeMessages")
        assertThat(t.gateSuffix()).contains("limpeza-de-spam")

        val loaded = t.loadSkill("Limpeza-De-Spam")
        assertThat(loaded["found"]).isEqualTo(true)
        assertThat(loaded["instructions"].toString()).contains("purgeMessages")
    }

    @Test
    fun `loadSkill on unknown name fails soft and lists what exists`() {
        dir.resolve("a.md").writeText("desc a")
        val loaded = tools().loadSkill("inexistente")
        assertThat(loaded["found"]).isEqualTo(false)
        assertThat(loaded["note"].toString()).contains("a")
    }
}
