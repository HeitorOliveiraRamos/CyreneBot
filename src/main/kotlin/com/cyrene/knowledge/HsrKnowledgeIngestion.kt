package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.io.File

/**
 * One-shot loader that turns the Kaggle Honkai: Star Rail CSV dataset into embedded vectors.
 *
 * Run-once by design: it only exists as a bean when `bot.knowledge.reindex=true`, so normal
 * bot startups don't touch it. To (re)build the knowledge base:
 *
 * ```
 * HSR_REINDEX=true HSR_DATA_DIR=src/main/resources/hsrdataset mvn spring-boot:run
 * # ...wait for "HSR reindex complete", Ctrl-C, then run normally.
 * ```
 *
 * ### Why this is schema-aware (not a generic row dumper)
 *
 * The dataset is relational: only `characters.csv` carries the readable name and descriptive
 * fields; every other file joins by `character_id` (e.g. `acheron`). A naive "one Document per
 * row" load would (a) strip the character's identity from each skill/eidolon and (b) embed
 * thousands of pure-number rows (`character_skill_scaling.csv` alone is ~5.5k rows of damage
 * coefficients) that are useless for semantic search and only dilute retrieval.
 *
 * Instead we resolve names from `characters.csv` and emit clean, self-contained documents:
 *  - **profile** — one per character: name, rarity, Path, Element, faction, release date.
 *  - **skill**   — one per skill, prefixed with the character's name + the skill description.
 *  - **eidolon** — one per eidolon, prefixed with the character's name + the eidolon text.
 *
 * The numeric-only files (`*_skill_scaling`, `*_stats`, `*_traces`) are intentionally skipped —
 * they answer "what's the exact coefficient at level 7", which isn't what a chat bot is asked.
 * Any *other* `.csv` you drop in (e.g. a future wiki/lore export) still gets a generic
 * one-Document-per-row pass, so the loader degrades gracefully for unknown schemas.
 */
@Component
@ConditionalOnProperty(name = ["bot.knowledge.reindex"], havingValue = "true")
class HsrKnowledgeIngestion(
    private val vectorStore: VectorStore,
    private val jdbcTemplate: JdbcTemplate,
    private val properties: BotProperties,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    private val csvMapper = CsvMapper()

    /** Known dataset files handled by the schema-aware path; everything else falls back to generic. */
    private companion object {
        const val CHARACTERS = "characters.csv"
        const val SKILLS = "character_skills.csv"
        const val EIDOLONS = "character_eidolons.csv"
        val SKIPPED = setOf("character_skill_scaling.csv", "character_stats.csv", "character_traces.csv")
    }

    override fun run(vararg args: String?) {
        val dir = File(properties.knowledge.dataDir)
        if (!dir.isDirectory) {
            log.error("HSR_REINDEX is on but data dir '{}' does not exist. Nothing to ingest.", dir.absolutePath)
            return
        }

        val byName = dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
            .associateBy { it.name }

        if (byName.isEmpty()) {
            log.error("No .csv files found under '{}'. Nothing to ingest.", dir.absolutePath)
            return
        }

        val docs = mutableListOf<Document>()

        // characters.csv is the spine: it gives us both the profile docs and the id→name map
        // every other file needs. Without it we can't resolve names, so skills/eidolons are skipped.
        val idToName = mutableMapOf<String, String>()
        byName[CHARACTERS]?.let { file ->
            val rows = readCsv(file)
            for (row in rows) {
                val id = row["character_id"]?.trim().orEmpty()
                val name = row["character_name"]?.trim().orEmpty()
                if (id.isNotEmpty() && name.isNotEmpty()) idToName[id] = name
            }
            docs += rows.mapNotNull { profileDocument(it) }
            log.info("characters.csv → {} character profiles", docs.size)
        } ?: log.warn("characters.csv not found — skill/eidolon names cannot be resolved.")

        byName[SKILLS]?.let { file ->
            val before = docs.size
            docs += readCsv(file).mapNotNull { skillDocument(it, idToName) }
            log.info("character_skills.csv → {} skill docs", docs.size - before)
        }

        byName[EIDOLONS]?.let { file ->
            val before = docs.size
            docs += readCsv(file).mapNotNull { eidolonDocument(it, idToName) }
            log.info("character_eidolons.csv → {} eidolon docs", docs.size - before)
        }

        // Generic fallback for any unknown csv (future lore/wiki exports), excluding the
        // numeric files we deliberately skip and the ones already handled above.
        val handled = setOf(CHARACTERS, SKILLS, EIDOLONS) + SKIPPED
        for ((name, file) in byName) {
            if (name in handled) continue
            val before = docs.size
            docs += readCsv(file).mapNotNull { genericRowDocument(it, file.nameWithoutExtension) }
            log.info("{} (generic) → {} docs", name, docs.size - before)
        }

        if (SKIPPED.any { it in byName }) {
            log.info("Skipped numeric-only files (not useful for semantic search): {}",
                SKIPPED.filter { it in byName })
        }

        if (docs.isEmpty()) {
            log.error("Parsed 0 usable documents — aborting before clearing the store.")
            return
        }

        log.info("Clearing vector_store and embedding {} documents...", docs.size)
        jdbcTemplate.execute("TRUNCATE TABLE vector_store")
        embedInBatches(docs)
        log.info("HSR reindex complete: {} documents embedded. Restart WITHOUT HSR_REINDEX to run normally.", docs.size)
    }

    // -------------------- document builders -------------------- //

    private fun profileDocument(row: Map<String, String>): Document? {
        val id = row["character_id"]?.trim().orEmpty()
        val name = row["character_name"]?.trim().orEmpty()
        if (name.isEmpty()) return null

        val text = buildString {
            append("$name — personagem de Honkai: Star Rail.\n")
            row["rarity"]?.let { append("Raridade: $it estrelas\n") }
            row["path"]?.let { append("Caminho (Path): $it\n") }
            row["element"]?.let { append("Elemento: $it\n") }
            row["faction"]?.takeIf { it.isNotBlank() }?.let { append("Facção: $it\n") }
            row["release_date"]?.takeIf { it.isNotBlank() }?.let { append("Data de lançamento: $it\n") }
            row["max_energy"]?.let { append("Energia máxima: $it\n") }
            row["base_spd"]?.let { append("Velocidade base (SPD): $it\n") }
        }.trim()

        return Document(text, metaOf("profile", name, id))
    }

    private fun skillDocument(row: Map<String, String>, idToName: Map<String, String>): Document? {
        val id = row["character_id"]?.trim().orEmpty()
        val name = resolveName(id, idToName)
        val skillName = row["skill_name"]?.trim().orEmpty()
        val description = row["description"]?.trim().orEmpty()
        if (skillName.isEmpty() && description.isEmpty()) return null

        val type = row["skill_type"]?.trim().orEmpty()
        val tag = row["skill_tag"]?.trim().orEmpty()
        val header = listOfNotNull(
            "$name — habilidade: $skillName",
            type.takeIf { it.isNotEmpty() }?.let { "($it${if (tag.isNotEmpty()) ", $tag" else ""})" },
        ).joinToString(" ")

        return Document("$header\n$description".trim(), metaOf("skill", name, id))
    }

    private fun eidolonDocument(row: Map<String, String>, idToName: Map<String, String>): Document? {
        val id = row["character_id"]?.trim().orEmpty()
        val name = resolveName(id, idToName)
        val n = row["eidolon"]?.trim().orEmpty()
        val eidolonName = row["eidolon_name"]?.trim().orEmpty()
        val description = row["description"]?.trim().orEmpty()
        if (description.isEmpty()) return null

        return Document(
            "$name — Eidolon $n: $eidolonName\n$description".trim(),
            metaOf("eidolon", name, id),
        )
    }

    /** Fallback: render an arbitrary row as `key: value` lines. Used for unknown CSV schemas. */
    private fun genericRowDocument(row: Map<String, String>, category: String): Document? {
        val lines = row.entries.mapNotNull { (k, v) ->
            v?.trim()?.takeIf { it.isNotEmpty() }?.let { "$k: $it" }
        }
        if (lines.isEmpty()) return null
        return Document("[$category]\n${lines.joinToString("\n")}", mapOf("category" to category))
    }

    private fun resolveName(id: String, idToName: Map<String, String>): String =
        idToName[id] ?: id.replaceFirstChar(Char::uppercase)

    private fun metaOf(category: String, name: String, id: String): Map<String, Any> = buildMap {
        put("category", category)
        put("name", name)
        if (id.isNotEmpty()) put("character_id", id)
    }

    // -------------------- io / embedding -------------------- //

    private fun readCsv(file: File): List<Map<String, String>> {
        val schema = CsvSchema.emptySchema().withHeader()
        val reader = csvMapper.readerForMapOf(String::class.java).with(schema)
        file.bufferedReader().use { br ->
            return reader.readValues<Map<String, String>>(br).readAll()
        }
    }

    private fun embedInBatches(docs: List<Document>) {
        val batchSize = properties.knowledge.batchSize.coerceAtLeast(1)
        val batches = docs.chunked(batchSize)
        batches.forEachIndexed { i, batch ->
            vectorStore.add(batch)
            log.info("  embedded batch {}/{} ({} docs)", i + 1, batches.size, batch.size)
        }
    }
}
