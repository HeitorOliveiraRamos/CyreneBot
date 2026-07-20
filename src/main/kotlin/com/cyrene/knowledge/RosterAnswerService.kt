package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacter
import com.cyrene.hsr.HsrCharacterService
import com.cyrene.hsr.HsrRepository
import com.cyrene.hsr.HsrTaxonomy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Questions ABOUT A TABLE rather than about one entity — "me fala 5 personagens de gelo
 * aleatórios", "quantos membros tem na facção Expresso Astral?", "quantas relíquias tem no total?",
 * "me da 5 cones da destruição".
 *
 * **Why this is not a text-to-SQL tool.** The obvious design is to let the model write the query;
 * the reason it isn't is that the knowledge path never reaches a tool-calling pass (retrieval here
 * is deterministic — see [KnowledgeGrounder]), and a local 8B/14B emitting SQL would be both a
 * reliability problem (a silently wrong filter reads exactly like a right one) and an injection
 * surface, in exchange for expressiveness these questions don't need. Every roster question
 * observed so far is a conjunction of closed-vocabulary filters plus a limit — which parses
 * deterministically and can't hallucinate.
 *
 * Characters are read from the in-memory gazetteer ([HsrCharacterService.all]), which already
 * carries path/element/rarity/faction, so a character question costs no query at all; item
 * questions read the three item tables. Path and element are compared through [HsrTaxonomy]
 * because beta rows store the English spellings.
 */
@Component
class RosterAnswerService(
    private val characters: HsrCharacterService,
    private val repo: HsrRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun answer(query: String): String? {
        val roster = characters.all()
        val ask = wantedRoster(query, roster.mapNotNull { it.faccao }) ?: return null
        log.debug("Deterministic roster answer for '{}' (ask {})", query, ask)
        return if (ask.entity == Entity.PERSONAGEM) characterAnswer(ask) else itemAnswer(ask)
    }

    private fun characterAnswer(ask: RosterAsk): String? {
        val matched = filter(characters.all(), ask)
        if (matched.isEmpty()) return null
        return render(collapseVariants(matched, ask) { characters.displayName(it.id) }, ask)
    }

    /** Cheap predicate for the intent gate: does this parse as a table question at all? */
    fun isTableQuestion(query: String): Boolean =
        runCatching { wantedRoster(query, characters.all().mapNotNull { it.faccao }) != null }
            .getOrDefault(false)

    /**
     * Items carry no element or faction, and only cones carry a Path/rarity — so a filter the
     * entity can't honour makes the question un-answerable here and falls through, rather than
     * being silently ignored (which would answer a different question than the one asked).
     */
    private fun itemAnswer(ask: RosterAsk): String? {
        if (ask.elemento != null || ask.faccao != null) return null
        val rows: List<Row> = when (ask.entity) {
            Entity.RELIQUIA -> repo.allReliquias().map { Row(it.nome, null) }
            Entity.ORNAMENTO -> repo.allOrnamentos().map { Row(it.nome, null) }
            Entity.CONE -> repo.allCones()
                .filter { c ->
                    (ask.caminho == null || HsrTaxonomy.canonicalPath(c.caminho) == ask.caminho) &&
                        (ask.raridade == null || c.raridade == ask.raridade)
                }
                .map { c ->
                    Row(c.nome, listOfNotNull(
                        c.caminho.takeIf { ask.caminho == null },
                        c.raridade?.let { "$it★" }.takeIf { ask.raridade == null },
                    ).joinToString(" · ").ifEmpty { null })
                }
            Entity.PERSONAGEM -> return null
        }
        if (rows.isEmpty()) return null
        // Relics and ornaments have no filterable facet, so only a COUNT is a real answer for
        // them; listing all 32 unprompted would be a wall.
        if (ask.entity != Entity.CONE && !ask.count && ask.limit >= DEFAULT_LIMIT) return null
        return render(rows, ask)
    }

    /** One rendered line: the display name plus whatever facets weren't already filtered on. */
    internal data class Row(val name: String, val facts: String?)

    /** Which table the question is about. */
    internal enum class Entity(val label: String) {
        PERSONAGEM("Personagens"),
        RELIQUIA("Relíquias"),
        ORNAMENTO("Ornamentos Planares"),
        CONE("Cones de Luz"),
    }

    /**
     * A parsed question: the table, the filters (null = unconstrained), how many to show, whether
     * to shuffle, and whether the user asked for a COUNT rather than a list.
     */
    internal data class RosterAsk(
        val entity: Entity = Entity.PERSONAGEM,
        val elemento: String? = null,
        val caminho: String? = null,
        val raridade: Int? = null,
        val faccao: String? = null,
        val limit: Int = DEFAULT_LIMIT,
        val random: Boolean = false,
        val count: Boolean = false,
    )

    internal companion object {
        /** Shown when the user names no number — enough to be useful, short enough to not wall. */
        internal const val DEFAULT_LIMIT = 20

        /** A count answer also names the entries when there are at most this many. */
        internal const val COUNT_WITH_NAMES = 15

        /** Words that make this a question about a table rather than about one entity. */
        private val ENTITY_CUES: Map<String, Entity> = listOf(
            Entity.PERSONAGEM to "personagem personagens chars characters unidade unidades " +
                "membro membros integrante integrantes",
            Entity.RELIQUIA to "reliquia reliquias relic relics",
            Entity.ORNAMENTO to "ornamento ornamentos ornament ornaments",
            Entity.CONE to "cone cones lightcone lightcones",
        ).flatMap { (e, words) -> words.split(' ').map { it to e } }.toMap()

        /**
         * Entity words that ALSO belong to Discord's server vocabulary. A question resting on one
         * of these needs a real filter — a faction, element, Path or rarity — not merely a count:
         * "quantos membros tem no servidor?" is moderation, "quantos membros tem na facção
         * Expresso Astral?" is game data, and a bare count cannot tell them apart.
         */
        private val SERVER_AMBIGUOUS = setOf("membro", "membros", "integrante", "integrantes")

        private val COUNT_WORDS = setOf("quantos", "quantas", "total")
        private val RANDOM_WORDS = setOf("aleatorio", "aleatoria", "aleatorios", "aleatorias", "random")

        /**
         * "5 estrelas" / "4*" / "3 star" — parsed (and removed) before the limit scan.
         * Range is 3–5, not 4–5: characters are only ever 4★/5★ but light cones go down to 3★
         * (25 of the 169), and "5 cones de luz da destruição 3 estrelas" silently ignored the
         * rarity while it was capped at [45].
         */
        private val RARITY = Regex("\\b([3-5])\\s*(?:estrelas?|stars?|\\*)")

        /** Any remaining standalone 1..50 is how many to show. */
        private val NUMBER = Regex("\\b([1-9]\\d?)\\b")

        /**
         * Parses a table question, or null when it isn't one. Requires BOTH an entity word and at
         * least one filter (or a count ask): "personagens" alone appears in plenty of questions
         * that are really about a specific character ("quais personagens combinam com a march"),
         * and answering those with a list would be worse than falling through to retrieval.
         * [factions] are matched by name against the query, longest first. Pure.
         */
        internal fun wantedRoster(query: String, factions: Collection<String> = emptyList()): RosterAsk? {
            val norm = HsrCharacterService.normalize(query)
            val tokens = norm.split(' ')
            // An item word wins over "personagens", which is the vaguer of the two and often just
            // scenery ("cones dos personagens de gelo" is a question about cones).
            val named = ENTITY_CUES.entries.filter { (word, _) -> word in tokens }.map { it.value }
            val entity = named.firstOrNull { it != Entity.PERSONAGEM } ?: named.firstOrNull() ?: return null

            val rarity = RARITY.find(norm)
            val raridade = rarity?.groupValues?.get(1)?.toInt()
            // Strip the rarity span so "personagens 5 estrelas" doesn't read 5 as the limit.
            val rest = rarity?.let { norm.removeRange(it.range) } ?: norm

            val faccao = HsrCharacterService.matchLongest(query, factions).firstOrNull()
            val elemento = HsrTaxonomy.elementIn(norm)
            val caminho = HsrTaxonomy.pathIn(norm)
            val count = tokens.any { it in COUNT_WORDS }
            val hasFilter = elemento != null || caminho != null || raridade != null || faccao != null
            if (!hasFilter && !count) return null
            // A server-ambiguous entity word standing on a bare count is not ours.
            val onlyAmbiguous = named.isNotEmpty() &&
                ENTITY_CUES.entries.none { (w, _) -> w in tokens && w !in SERVER_AMBIGUOUS }
            if (onlyAmbiguous && !hasFilter) return null

            return RosterAsk(
                entity = entity,
                elemento = elemento,
                caminho = caminho,
                raridade = raridade,
                faccao = faccao,
                limit = NUMBER.find(rest)?.groupValues?.get(1)?.toInt()?.coerceIn(1, 50) ?: DEFAULT_LIMIT,
                random = tokens.any { it in RANDOM_WORDS },
                count = count,
            )
        }

        /** The roster narrowed by whichever filters [ask] carries. Pure. */
        internal fun filter(roster: Collection<HsrCharacter>, ask: RosterAsk): List<HsrCharacter> =
            roster.filter { c ->
                (ask.elemento == null || HsrTaxonomy.canonicalElement(c.elemento) == ask.elemento) &&
                    (ask.caminho == null || HsrTaxonomy.canonicalPath(c.caminho) == ask.caminho) &&
                    (ask.raridade == null || c.raridade == ask.raridade) &&
                    (ask.faccao == null || c.faccao == ask.faccao)
            }

        /**
         * Collapses a character's alternate forms into ONE entry. A "how many" question is about
         * characters, not table rows, and `personagem_hsr` stores every Trailblazer Path (and
         * gender) as its own row — so "quantos membros tem na facção Expresso Astral?" counted 15
         * where a player counts 7, because ten of those rows are Trailblazers. Grouping is by base
         * name, so genuinely distinct units keep their own entry, and a question that already
         * filtered by Path produces single-row groups anyway. Pure.
         */
        internal fun collapseVariants(
            matched: List<HsrCharacter>,
            ask: RosterAsk,
            naming: (HsrCharacter) -> String,
        ): List<Row> = matched
            .groupBy { HsrCharacterService.normalize(it.baseName) }
            .values
            .map { group ->
                if (group.size == 1) Row(naming(group[0]), facts(group[0], ask))
                else Row(group[0].baseName, "${group.size} formas")
            }

        /** The facets of [c] worth showing — the ones the question didn't already filter on. */
        internal fun facts(c: HsrCharacter, ask: RosterAsk): String? = listOfNotNull(
            HsrTaxonomy.canonicalPath(c.caminho).takeIf { ask.caminho == null },
            c.raridade?.let { "$it★" }.takeIf { ask.raridade == null },
        ).joinToString(" · ").ifEmpty { null }

        /**
         * A count line, or a bulleted list capped at [RosterAsk.limit]. Sorted by name unless the
         * user asked for random, in which case the shuffle happens BEFORE the cap (otherwise "5
         * aleatórios" would always be the same five). Pure.
         */
        internal fun render(rows: List<Row>, ask: RosterAsk): String {
            val filters = listOfNotNull(
                ask.raridade?.let { "$it★" }, ask.elemento, ask.caminho, ask.faccao,
            )
            val title = ask.entity.label + if (filters.isEmpty()) "" else " " + filters.joinToString(" · ")

            // A count question is nearly always followed by "which ones?", and a short list costs
            // a few lines — so answer both at once whenever the list is small enough to not wall.
            if (ask.count) {
                val line = "**$title**: ${rows.size}"
                if (rows.size > COUNT_WITH_NAMES) return line
                return (listOf(line) + rows.sortedBy { it.name }.map { r ->
                    "- ${r.name}" + r.facts?.let { " — $it" }.orEmpty()
                }).joinToString("\n")
            }

            val ordered = if (ask.random) rows.shuffled() else rows.sortedBy { it.name }
            val shown = ordered.take(ask.limit)
            val header = if (shown.size < rows.size) {
                "**$title** (${shown.size} de ${rows.size})"
            } else {
                "**$title** (${rows.size})"
            }
            return (listOf(header) + shown.map { r ->
                "- ${r.name}" + r.facts?.let { " — $it" }.orEmpty()
            }).joinToString("\n")
        }
    }
}
