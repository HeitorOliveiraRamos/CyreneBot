package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacterService
import com.cyrene.hsr.HsrRepository
import com.cyrene.hsr.NamedText
import com.cyrene.hsr.PersonagemHsr
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Deterministic, LLM-free answers for kit questions about a specific ability or eidolon —
 * "o que a ult da robin faz?", "o que a e1 da acheron faz?", "qual o talento do welt?".
 *
 * Seam 2: the answer is read straight from the structured `personagem_hsr` row ([HsrRepository]),
 * not by parsing vector-store docs. The query routing ([wantedKit]) is unchanged player-vocabulary
 * parsing; only the source and render moved to columns — every ability/eidolon/trace is already a
 * `_nome`/`_descricao` pair, so composing the answer is selection + formatting, not generation.
 *
 * The character is resolved through the multilingual gazetteer ([HsrCharacterService.findInText]),
 * which (post-cutover) is `personagem_hsr` itself — so an EN/ES name resolves to the same row we
 * render. "kit" renders the whole standardized sheet: profile header, the 5 abilities in canonical
 * order, memoespírito/euforia when the unit has them, the 3 major traces, the 6 eidolons.
 */
@Component
class KitAnswerService(
    private val repo: HsrRepository,
    private val characters: HsrCharacterService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun answer(query: String): String? {
        val ask = wantedKit(query) ?: return null
        val ids = characters.findInText(query).map { it.id }.take(MAX_CHARACTERS)
        if (ids.isEmpty()) return null
        val sections = repo.personagens(ids)
            .mapNotNull { renderKit(it, ask, characters.displayName(it.characterId)) }
        if (sections.isEmpty()) return null
        log.debug("Deterministic kit answer for '{}' (ask {})", query, ask)
        return sections.joinToString("\n\n")
    }

    /**
     * Ability slots in canonical kit order; [tag] is the PT header label. The last three exist
     * only on some units — memoespírito on the 8 Recordação characters, euforia on the 7 Euforia
     * ones — and render as nothing for everyone else, so they can sit in the "whole kit" set
     * without special-casing.
     */
    internal enum class SkillKind(val tag: String) {
        BASIC("ATQ Básico"),
        SKILL("Perícia"),
        ULTIMATE("Perícia Suprema"),
        TALENT("Talento"),
        TECHNIQUE("Técnica"),
        MEMO_SKILL("Perícia do Memoespírito"),
        MEMO_TALENT("Talento do Memoespírito"),
        EUPHORIA("Perícia da Euforia"),
    }

    /**
     * What the question asks for. [eidolons] null = eidolons not asked; empty = all of
     * them ("quais os eidolons…"); otherwise the specific numbers ("e1", "eidolon 2").
     * [traces] = the A2/A4/A6 majors; [profile] = the rarity/path/element header (+ memoespírito/
     * euforia), only ever set by the full-"kit" ask.
     */
    internal data class KitAsk(
        val kinds: Set<SkillKind>,
        val eidolons: Set<Int>?,
        val traces: Boolean = false,
        val profile: Boolean = false,
        val fields: Set<CharField> = emptySet(),
    )

    /**
     * A single scalar column of `personagem_hsr` the user can ask for by name ("me fala a
     * descrição da himeko", "qual a facção do welt?"). Distinct from [KitAsk.profile], which
     * renders the rarity/path/element header as a block: this answers with ONE field and nothing
     * else, which is the whole point of asking for it.
     */
    internal enum class CharField(val tag: String, val pick: (PersonagemHsr) -> String?) {
        DESCRICAO("Descrição", { it.descricao }),
        FACCAO("Facção", { it.faccao }),
        RARIDADE("Raridade", { it.raridade?.let { r -> "$r estrelas" } }),
        CAMINHO("Caminho", { it.caminho }),
        ELEMENTO("Elemento", { it.elemento }),
    }

    internal companion object {
        /** Same listy-question ceiling as the build path. */
        private const val MAX_CHARACTERS = 4

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

        /** The A2/A4/A6 majors — what players call "passiva"/"traço". */
        private val TRACE_WORDS = setOf(
            "traco", "tracos", "trace", "traces",
            "passiva", "passivas", "passivo", "passivos",
        )

        /** The whole sheet: profile header + abilities + traces + eidolons. */
        private val KIT_WORDS = setOf("kit", "kits")

        /**
         * "talento do memoespírito" / "perícia da euforia" — an ability word BOUND to the
         * memosprite or euphoria by a possessive. Matched (and consumed) as a phrase for the same
         * reason "perícia suprema" is: the bare ability token would otherwise ALSO cue the base
         * slot, and "o que o talento do memoespírito da hyacine faz?" answered with her regular
         * Talento. The binding must be tight — "a perícia da desbravadora da euforia" is a
         * question about the base skill of the Euphoria Trailblazer, and correctly misses here,
         * because "euforia" there is the Path, not the ability.
         */
        private val MEMO_PHRASE =
            Regex("\\b(pericia|habilidade|skill|talento|talent)\\s+(?:d[oae]\\s+)?(?:memoespiritos?|memosprites?)\\b")
        private val EUPHORIA_PHRASE =
            Regex("\\b(?:pericia|habilidade|skill)\\s+(?:d[oae]\\s+)?(?:euforia|elation)\\b")

        /**
         * Player vocabulary → a single character column. Hand-mapped on purpose: the set of
         * columns is closed and small, so a lookup table can't drift the way a generated
         * projection could, and a word that isn't here simply leaves the question to the
         * retrieval path.
         */
        // ponytail: hand-kept alias map; add a line when a word players actually use turns up missing.
        private val FIELD_CUES: Map<String, CharField> = listOf(
            CharField.DESCRICAO to "descricao descricoes description descreve",
            CharField.FACCAO to "faccao faccoes faction afiliacao organizacao",
            CharField.RARIDADE to "raridade rarity estrelas",
            CharField.CAMINHO to "caminho caminhos path senda",
            CharField.ELEMENTO to "elemento elementos element",
        ).flatMap { (field, words) -> words.split(' ').map { it to field } }.toMap()

        /** Bare mentions — "o que o memoespírito da castorice faz?", with no ability word at all. */
        private val MEMO_WORDS = setOf("memoespirito", "memoespiritos", "memosprite", "memosprites")
        private val EUPHORIA_WORDS = setOf("euforia", "elation")

        /**
         * Raw cue scan, ignoring the build vocabulary. Null when nothing kit-shaped.
         * "perícia suprema" as a phrase is ONLY the ultimate — the "pericia" token must
         * not additionally cue SKILL when "suprema" follows it. Pure.
         */
        internal fun rawKitAsk(query: String): KitAsk? {
            val norm = HsrCharacterService.normalize(query)
            val kinds = mutableSetOf<SkillKind>()
            // Memosprite/euphoria phrases are consumed BEFORE the token scan, so the ability word
            // inside them ("talento" in "talento do memoespírito") can't also cue its base slot.
            var scan = MEMO_PHRASE.replace(norm) { m ->
                kinds += if (m.groupValues[1].startsWith("talen")) SkillKind.MEMO_TALENT else SkillKind.MEMO_SKILL
                " "
            }
            scan = EUPHORIA_PHRASE.replace(scan) { kinds += SkillKind.EUPHORIA; " " }
            val tokens = scan.split(' ')
            tokens.forEachIndexed { i, t ->
                if (t == "pericia" && tokens.getOrNull(i + 1) == "suprema") return@forEachIndexed
                KIND_CUES[t]?.let { kinds += it }
            }
            // Bare mention with no ability word: "o que o memoespírito da X faz?" means all of it.
            // Gated on kinds being empty so a Path word ("desbravador da euforia") next to a real
            // ability cue stays a Path — [HsrCharacterService.disambiguate] is what consumes it there.
            if (kinds.isEmpty()) {
                if (tokens.any { it in MEMO_WORDS }) kinds += listOf(SkillKind.MEMO_SKILL, SkillKind.MEMO_TALENT)
                else if (tokens.any { it in EUPHORIA_WORDS }) kinds += SkillKind.EUPHORIA
            }
            if (tokens.any { it in ALL_KINDS_WORDS }) kinds += SkillKind.entries
            val nums = GameKnowledgeTools.EIDOLON_NUM.findAll(norm)
                .map { it.groupValues[1].toInt() }.toSet()
            var eidolons = when {
                nums.isNotEmpty() -> nums
                tokens.any { it in EIDOLON_WORDS } -> emptySet()
                else -> null
            }
            var traces = tokens.any { it in TRACE_WORDS }
            var profile = false
            if (tokens.any { it in KIT_WORDS }) {
                kinds += SkillKind.entries
                traces = true
                profile = true
                if (eidolons == null) eidolons = emptySet()
            }
            val fields = tokens.mapNotNull { FIELD_CUES[it] }.toSet()
            if (kinds.isEmpty() && eidolons == null && !traces && fields.isEmpty()) return null
            return KitAsk(kinds, eidolons, traces, profile, fields)
        }

        /** [rawKitAsk] gated on the build vocabulary: a question asking for BOTH families
         *  ("qual a build e a ult do welt?") is a composition job — the LLM path keeps it. */
        internal fun wantedKit(query: String): KitAsk? =
            rawKitAsk(query)?.takeIf { BuildAnswerService.wantedLabels(query).isEmpty() }

        private fun skillOf(p: PersonagemHsr, k: SkillKind): NamedText = when (k) {
            SkillKind.BASIC -> p.atqBasico
            SkillKind.SKILL -> p.pericia
            SkillKind.ULTIMATE -> p.periciaSuprema
            SkillKind.TALENT -> p.talento
            SkillKind.TECHNIQUE -> p.tecnica
            SkillKind.MEMO_SKILL -> p.periciaMemoespirito
            SkillKind.MEMO_TALENT -> p.talentoMemoespirito
            SkillKind.EUPHORIA -> p.periciaEuforia
        }

        /**
         * The asked pieces of [p]'s kit as bold-headed blocks in fixed sheet order: profile
         * header, abilities in canonical order, memoespírito/euforia (full-kit only), major
         * traces, eidolons by number. Null when this character has none of the asked pieces, so
         * the caller falls through. Pure — [PersonagemHsr] is a plain model, so this is
         * unit-testable without a DB.
         */
        internal fun renderKit(
            p: PersonagemHsr,
            ask: KitAsk,
            who: String = p.nome ?: p.nomeEn ?: p.characterId,
        ): String? {
            val blocks = mutableListOf<String>()
            if (ask.profile) profileHeader(p, who)?.let { blocks += it }
            // Single-column asks render as one labeled line each, in declaration order.
            for (field in ask.fields.sortedBy { it.ordinal }) {
                field.pick(p)?.takeIf { it.isNotBlank() }?.let { blocks += "**$who — ${field.tag}**\n$it" }
            }
            // Memoespírito/euforia are ordinary kinds now (they used to render only under the
            // full-kit profile flag), so a question naming one directly gets exactly that block.
            for (kind in ask.kinds.sortedBy { it.ordinal }) {
                block(who, kind.tag, skillOf(p, kind))?.let { blocks += it }
            }
            if (ask.traces) {
                p.tracos.forEach { block(who, "traço maior", it)?.let { b -> blocks += b } }
            }
            ask.eidolons?.let { nums ->
                p.eidolons.forEachIndexed { i, eid ->
                    val n = i + 1
                    if ((nums.isEmpty() || n in nums) && !eid.isBlank) {
                        blocks += block(who, "Eidolon $n", eid) ?: return@forEachIndexed
                    }
                }
            }
            return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        }

        /** `**{who} — {rarity/path/element}**` header; null when the row carries none of them. */
        private fun profileHeader(p: PersonagemHsr, who: String): String? {
            val facts = listOfNotNull(
                p.raridade?.let { "Raridade: $it estrelas" },
                p.caminho?.let { "Caminho: $it" },
                p.elemento?.let { "Elemento: $it" },
            )
            if (facts.isEmpty()) return null
            return (listOf("**$who — Honkai: Star Rail**") + facts).joinToString("\n")
        }

        /** `**{who} — {label}: {nome}**\n{descricao}` for one ability/trace/eidolon; null if blank. */
        private fun block(who: String, label: String, nt: NamedText): String? {
            if (nt.isBlank) return null
            val header = "**$who — $label" + (nt.nome?.let { ": $it" } ?: "") + "**"
            return nt.descricao?.takeIf { it.isNotBlank() }?.let { "$header\n$it" } ?: header
        }
    }
}
