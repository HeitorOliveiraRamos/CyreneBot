package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacterService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Deterministic, LLM-free answers for kit questions about a specific ability or eidolon —
 * "o que a ult da robin faz?", "o que a e1 da acheron faz?", "qual o talento do welt?".
 *
 * Same contract as [BuildAnswerService]: the srs kit docs are already one self-contained
 * PT-BR doc per ability/eidolon with a typed header ("Robin — habilidade: … (Perícia
 * Suprema)"), so composing the answer is selection + formatting, not generation. The doc
 * is fetched by exact metadata name (srs uses slug character_ids, so the game-id join the
 * build path uses doesn't apply here), the asked kind is matched against the header's
 * exact type tag — exact, because "Perícia" is a prefix of "Perícia Suprema" — and the doc
 * is rendered verbatim under a bold header.
 *
 * Deliberately NOT routed here: traces/"passiva" (their docs are English — the voice pass
 * translating them is the one legit model job) and "kit" (spans traces too). Those fall
 * through, like every null from this service.
 */
@Component
class KitAnswerService(
    private val jdbc: JdbcTemplate,
    private val characters: HsrCharacterService,
    private val tools: GameKnowledgeTools,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun answer(query: String): String? {
        val ask = wantedKit(query) ?: return null
        val names = subjectNames(query)
        if (names.isEmpty()) return null
        val docs = kitDocs(names)
        if (docs.isEmpty()) return null
        val sections = docs.groupBy { it.name }.values
            .mapNotNull { charDocs ->
                renderKit(
                    skillDocs = charDocs.filter { it.category == "skill" }.map { it.content },
                    eidolonDocs = charDocs.filter { it.category == "eidolon" }.map { it.content },
                    ask = ask,
                )
            }
        if (sections.isEmpty()) return null
        log.debug("Deterministic kit answer for '{}' (ask {})", query, ask)
        return sections.joinToString("\n\n")
    }

    /**
     * Names to fetch docs under. KB metadata names first ([GameKnowledgeTools.matchNames]
     * handles accents and SP-form separators); when the user asked in another language
     * ("march 7th"), the gazetteer's aliases are the fallback — whichever equals the
     * stored doc name wins, the rest match nothing and cost nothing.
     */
    private fun subjectNames(query: String): List<String> {
        val kb = GameKnowledgeTools.matchNames(query, tools.kbNames())
        if (kb.isNotEmpty()) return kb
        return characters.findInText(query).flatMap { it.names }.distinct()
    }

    private data class KitDoc(val name: String, val category: String, val content: String)

    /** skill + eidolon docs stored under [names]. Fail-open: empty falls through to retrieval. */
    private fun kitDocs(names: List<String>): List<KitDoc> = try {
        val placeholders = names.joinToString(",") { "?" }
        jdbc.queryForList(
            "SELECT content, metadata->>'category' AS category, metadata->>'name' AS name " +
                "FROM vector_store WHERE metadata->>'category' IN ('skill','eidolon') " +
                "AND metadata->>'name' IN ($placeholders)",
            *names.toTypedArray(),
        ).mapNotNull { row ->
            val content = row["content"] as? String ?: return@mapNotNull null
            KitDoc(row["name"] as? String ?: "", row["category"] as? String ?: "", content)
        }
    } catch (e: Exception) {
        log.warn("kit doc fetch failed for {}: {}", names, e.message)
        emptyList()
    }

    /** Ability slots in canonical kit order; [tags] are the exact header type tags (PT + EN). */
    internal enum class SkillKind(vararg val tags: String) {
        BASIC("ATQ Básico", "Basic ATK"),
        SKILL("Perícia", "Skill"),
        ULTIMATE("Perícia Suprema", "Ultimate"),
        TALENT("Talento", "Talent"),
        TECHNIQUE("Técnica", "Technique"),
    }

    /**
     * What the question asks for. [eidolons] null = eidolons not asked; empty = all of
     * them ("quais os eidolons…"); otherwise the specific numbers ("e1", "eidolon 2").
     */
    internal data class KitAsk(val kinds: Set<SkillKind>, val eidolons: Set<Int>?)

    internal companion object {

        /** Player vocabulary → ability kind, over normalized tokens. */
        private val KIND_CUES: Map<String, SkillKind> = mapOf(
            "basico" to SkillKind.BASIC, "basica" to SkillKind.BASIC,
            "skill" to SkillKind.SKILL, "habilidade" to SkillKind.SKILL,
            "pericia" to SkillKind.SKILL,
            "ult" to SkillKind.ULTIMATE, "ulti" to SkillKind.ULTIMATE,
            "ultimate" to SkillKind.ULTIMATE, "ultimato" to SkillKind.ULTIMATE,
            "suprema" to SkillKind.ULTIMATE,
            "talento" to SkillKind.TALENT, "talentos" to SkillKind.TALENT,
            "talent" to SkillKind.TALENT,
            "tecnica" to SkillKind.TECHNIQUE, "tecnicas" to SkillKind.TECHNIQUE,
            "technique" to SkillKind.TECHNIQUE,
        )

        /** Plurals that mean the whole ability set ("quais as habilidades da robin?"). */
        private val ALL_KINDS_WORDS = setOf("habilidades", "skills")

        private val EIDOLON_WORDS = setOf("eidolon", "eidolons")

        /** First-line "Eidolon N" of an eidolon doc. */
        private val EIDOLON_HEADER = Regex("Eidolon ([1-6])")

        /** Last "(…)" group of a skill-doc header — the ability's type tag. */
        private val TYPE_TAG = Regex("\\(([^()]+)\\)\\s*$")

        /**
         * Raw cue scan, ignoring the build vocabulary. Null when nothing kit-shaped.
         * "perícia suprema" as a phrase is ONLY the ultimate — the "pericia" token must
         * not additionally cue SKILL when "suprema" follows it. Pure.
         */
        internal fun rawKitAsk(query: String): KitAsk? {
            val norm = HsrCharacterService.normalize(query)
            val tokens = norm.split(' ')
            val kinds = mutableSetOf<SkillKind>()
            tokens.forEachIndexed { i, t ->
                if (t == "pericia" && tokens.getOrNull(i + 1) == "suprema") return@forEachIndexed
                KIND_CUES[t]?.let { kinds += it }
            }
            if (tokens.any { it in ALL_KINDS_WORDS }) kinds += SkillKind.entries
            val nums = GameKnowledgeTools.EIDOLON_NUM.findAll(norm)
                .map { it.groupValues[1].toInt() }.toSet()
            val eidolons = when {
                nums.isNotEmpty() -> nums
                tokens.any { it in EIDOLON_WORDS } -> emptySet()
                else -> null
            }
            if (kinds.isEmpty() && eidolons == null) return null
            return KitAsk(kinds, eidolons)
        }

        /** [rawKitAsk] gated on the build vocabulary: a question asking for BOTH families
         *  ("qual a build e a ult do welt?") is a composition job — the LLM path keeps it. */
        internal fun wantedKit(query: String): KitAsk? =
            rawKitAsk(query)?.takeIf { BuildAnswerService.wantedLabels(query).isEmpty() }

        /**
         * The asked docs rendered verbatim — bold header line, description as stored —
         * abilities in canonical kit order, then eidolons by number. Null when this
         * character has none of the asked docs, so the caller falls through. Pure.
         */
        internal fun renderKit(skillDocs: List<String>, eidolonDocs: List<String>, ask: KitAsk): String? {
            val blocks = mutableListOf<String>()
            for (kind in ask.kinds.sortedBy { it.ordinal }) {
                skillDocs.filter { typeTag(it) in kind.tags }.forEach { blocks += bolded(it) }
            }
            ask.eidolons?.let { nums ->
                eidolonDocs.mapNotNull { d -> eidolonNumber(d)?.let { n -> n to d } }
                    .filter { (n, _) -> nums.isEmpty() || n in nums }
                    .sortedBy { (n, _) -> n }
                    .forEach { (_, d) -> blocks += bolded(d) }
            }
            if (blocks.isEmpty()) return null
            return blocks.joinToString("\n\n")
        }

        internal fun typeTag(doc: String): String? =
            TYPE_TAG.find(doc.lineSequence().first())?.groupValues?.get(1)?.trim()

        private fun eidolonNumber(doc: String): Int? =
            EIDOLON_HEADER.find(doc.lineSequence().first())?.groupValues?.get(1)?.toInt()

        private fun bolded(doc: String): String {
            val lines = doc.trim().lines()
            val body = lines.drop(1).joinToString("\n").trim()
            return if (body.isEmpty()) "**${lines.first()}**" else "**${lines.first()}**\n$body"
        }
    }
}
