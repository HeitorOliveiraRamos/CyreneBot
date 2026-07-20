package com.cyrene.knowledge

import com.cyrene.hsr.ConeDeLuz
import com.cyrene.hsr.HsrCharacter
import com.cyrene.hsr.HsrCharacterService
import com.cyrene.hsr.HsrRepository
import com.cyrene.hsr.HsrTaxonomy
import com.cyrene.hsr.ItemEffect
import com.cyrene.knowledge.RosterAnswerService.Entity
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A structured query over the V17 tables — the generalization of [RosterAnswerService.RosterAsk]
 * that the flat filter+limit shape can't express: GROUP BY a facet ("5 personagens de cada
 * elemento"), an ordinal into a build's ranked item list ("o terceiro melhor cone pro Phainon"),
 * and an indefinite subject resolved by filter ("a build de um personagem de gelo").
 *
 * Every field is a CLOSED value: entity/facet/project are enums, elemento/caminho are canonical
 * [HsrTaxonomy] labels, faccao is a known faction name, characterIds come from the gazetteer.
 * That's the whole safety story — a plan that reaches [PlanAnswerService.execute] cannot name a
 * table, column or value that doesn't exist, no matter which tier (regex or LLM) produced it.
 */
internal data class QueryPlan(
    val entity: Entity,
    val elemento: String? = null,
    val caminho: String? = null,
    val raridade: Int? = null,
    val faccao: String? = null,
    val characterIds: List<String> = emptyList(),
    /** Group the listing by this facet; [limit] becomes per-group. */
    val groupBy: Facet? = null,
    /** 1-based index into the build's ranked (best-first) item list of [entity]. */
    val ordinal: Int? = null,
    /** Resolve the subject by drawing ONE random row matching the filters. */
    val pick: Boolean = false,
    /** Build-doc facet labels to render for a BUILD projection (see [BuildAnswerService]). */
    val labels: Set<String> = emptySet(),
    val project: Project = Project.LIST,
    val limit: Int = RosterAnswerService.DEFAULT_LIMIT,
    val random: Boolean = false,
) {
    /** The flat part of the plan, for the paths [RosterAnswerService] already renders. */
    fun toRosterAsk() = RosterAnswerService.RosterAsk(
        entity = entity, elemento = elemento, caminho = caminho, raridade = raridade,
        faccao = faccao, limit = limit, random = random, count = project == Project.COUNT,
    )
}

/** Facets a listing can be grouped by. Characters carry all four; cones only CAMINHO/RARIDADE. */
internal enum class Facet(val label: String) {
    ELEMENTO("Elemento"), CAMINHO("Caminho"), RARIDADE("Raridade"), FACCAO("Facção")
}

/** What the answer shows: the names, a build render, an item's effect text, or a count. */
internal enum class Project { LIST, BUILD, EFEITO, COUNT }

/**
 * Executes [QueryPlan]s and parses the question shapes that produce them. Two tiers feed it:
 *
 *  1. **Deterministic** ([parse]) — the same closed-vocabulary token parsing as the other
 *     answer services, extended with the group/ordinal/pick cues. Zero LLM latency; runs
 *     FIRST in the knowledge chain because an ordinal ask ("terceiro melhor cone…") would
 *     otherwise be swallowed by [BuildAnswerService]'s full ranked-list render.
 *  2. **LLM planner** ([parseLlmPlan]) — [com.cyrene.ai.OllamaAiService] has the brain model
 *     emit the same plan as JSON for phrasings the token parser can't anticipate. Strictly
 *     validated field by field; ANY unresolvable value rejects the whole plan, so this tier
 *     can only ever add answers, never a hallucinated filter.
 *
 * Execution composes primitives that already exist — [RosterAnswerService.filter]/render,
 * [BuildAnswerService.renderBuild], [HsrRepository.builds] (item FKs pre-resolved, slot order =
 * best-first) — so the answer is selection + a fixed template, never generation. Null whenever
 * the plan isn't fully honourable (a filter the entity lacks, an ordinal past the list); the
 * caller falls through, same contract as every other deterministic service.
 */
@Component
class PlanAnswerService(
    private val repo: HsrRepository,
    private val characters: HsrCharacterService,
    private val rosterAnswers: RosterAnswerService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun answer(query: String): String? {
        val plan = parseFor(query) ?: return null
        val body = execute(plan) ?: return null
        log.debug("Deterministic plan answer for '{}' ({})", query, plan)
        return body
    }

    /** Cheap predicate for the intent gate: does this parse as a plan question at all? */
    fun isPlanQuestion(query: String): Boolean =
        runCatching { parseFor(query) != null }.getOrDefault(false)

    private fun parseFor(query: String): QueryPlan? = parse(
        query,
        characters.all().mapNotNull { it.faccao },
        characters.findInText(query).map { it.id },
    )

    // -------------------- execution -------------------- //

    internal fun execute(plan: QueryPlan): String? = when {
        plan.ordinal != null -> ordinalAnswer(plan)
        plan.pick -> pickAnswer(plan)
        plan.groupBy != null -> groupAnswer(plan)
        plan.characterIds.isNotEmpty() && plan.project == Project.BUILD -> namedBuildAnswer(plan)
        else -> rosterAnswers.answer(plan.toRosterAsk())
    }

    /** The Nth-best recommended item of each named character's build, with its effect text. */
    private fun ordinalAnswer(plan: QueryPlan): String? {
        val n = plan.ordinal ?: return null
        if (plan.characterIds.isEmpty()) return null
        val byId = repo.builds(plan.characterIds).associateBy { it.characterId }
        val sections = plan.characterIds.map { id ->
            val v = byId[id] ?: return null
            val items = when (plan.entity) {
                Entity.CONE -> v.cones
                Entity.RELIQUIA -> v.reliquias
                Entity.ORNAMENTO -> v.ornamentos
                Entity.PERSONAGEM -> return null
            }
            // Ordinal past the list = un-answerable here; falling through lets
            // [BuildAnswerService] render the full ranked list instead, which shows the user
            // how many options actually exist.
            renderOrdinal(characters.displayName(id), n, plan.entity, items.getOrNull(n - 1) ?: return null)
        }
        return sections.joinToString("\n\n")
    }

    /** One random subject matching the filters, then the asked projection of it. */
    private fun pickAnswer(plan: QueryPlan): String? = when (plan.entity) {
        Entity.PERSONAGEM -> {
            val ids = RosterAnswerService.filter(characters.all(), plan.toRosterAsk()).map { it.id }
            if (ids.isEmpty()) null
            else if (plan.labels.isEmpty()) {
                // No build facet asked: the answer IS the draw.
                repo.personagens(ids).randomOrNull()?.let { p ->
                    pickLine(characters.displayName(p.characterId), plan)
                }
            } else {
                // Draw among the characters that HAVE a build, so the projection always lands.
                repo.builds(ids).randomOrNull()?.let { v ->
                    val who = characters.displayName(v.characterId)
                    BuildAnswerService.renderBuild(v, plan.labels, who)
                        ?.let { "${pickLine(who, plan)}\n\n$it" }
                }
            }
        }
        Entity.CONE -> {
            // Cones carry no element or faction — same unhonoured-filter guard as the roster path.
            if (plan.elemento != null || plan.faccao != null) null
            else repo.allCones()
                .filter { c ->
                    (plan.caminho == null || HsrTaxonomy.canonicalPath(c.caminho) == plan.caminho) &&
                        (plan.raridade == null || c.raridade == plan.raridade)
                }
                .randomOrNull()
                ?.let { c -> "${pickLine(c.nome, plan)}\n${renderCone(c)}" }
        }
        // ponytail: random relic/ornament draws haven't been asked for; add a branch when they are.
        else -> null
    }

    /** The filtered roster (or cone table) grouped by a facet, [QueryPlan.limit] per group. */
    private fun groupAnswer(plan: QueryPlan): String? {
        val facet = plan.groupBy ?: return null
        val groups: Map<String, List<RosterAnswerService.Row>> = when (plan.entity) {
            Entity.PERSONAGEM -> {
                val matched = RosterAnswerService.filter(characters.all(), plan.toRosterAsk())
                matched.groupBy { charFacet(it, facet) }
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
                    .mapValues { (_, group) ->
                        RosterAnswerService.collapseVariants(group, suppressGrouped(plan, facet)) {
                            characters.displayName(it.id)
                        }
                    }
            }
            Entity.CONE -> {
                if (plan.elemento != null || plan.faccao != null) return null
                if (facet != Facet.CAMINHO && facet != Facet.RARIDADE) return null
                repo.allCones()
                    .filter { c ->
                        (plan.caminho == null || HsrTaxonomy.canonicalPath(c.caminho) == plan.caminho) &&
                            (plan.raridade == null || c.raridade == plan.raridade)
                    }
                    .groupBy { c ->
                        if (facet == Facet.CAMINHO) HsrTaxonomy.canonicalPath(c.caminho) else c.raridade?.let { "$it★" }
                    }
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
                    .mapValues { (_, group) ->
                        group.map { c ->
                            RosterAnswerService.Row(c.nome, coneFacts(c, facet))
                        }
                    }
            }
            else -> return null
        }
        return renderGroups(plan, groups)
    }

    /** Full/faceted build render for named characters — the LLM tier's rescue for build
     *  phrasings [BuildAnswerService]'s cue words missed ("como equipar o Phainon?"). */
    private fun namedBuildAnswer(plan: QueryPlan): String? {
        val byId = repo.builds(plan.characterIds).associateBy { it.characterId }
        val labels = plan.labels.ifEmpty { GameKnowledgeTools.BUILD_LINE_LABELS.toSet() }
        val sections = plan.characterIds.map { id ->
            BuildAnswerService.renderBuild(byId[id] ?: return null, labels, characters.displayName(id))
                ?: return null
        }
        return sections.joinToString("\n\n")
    }

    private fun charFacet(c: HsrCharacter, f: Facet): String? = when (f) {
        Facet.ELEMENTO -> HsrTaxonomy.canonicalElement(c.elemento)
        Facet.CAMINHO -> HsrTaxonomy.canonicalPath(c.caminho)
        Facet.RARIDADE -> c.raridade?.let { "$it★" }
        Facet.FACCAO -> c.faccao
    }

    internal companion object {

        /** Same listy-question ceiling as the build/kit paths. */
        private const val MAX_CHARACTERS = 4

        private val JSON = ObjectMapper()

        /** Ordinal vocabulary → the 1-based build slot. Slots only go to 3 ("melhor" alone is
         *  NOT here: "o melhor cone" keeps today's full ranked-list answer). */
        private val ORDINAL_WORDS = mapOf(
            "primeiro" to 1, "primeira" to 1, "1o" to 1, "1a" to 1,
            "segundo" to 2, "segunda" to 2, "2o" to 2, "2a" to 2,
            "terceiro" to 3, "terceira" to 3, "3o" to 3, "3a" to 3,
        )

        /**
         * Tokens allowed right after an ordinal word for it to count as ranking talk.
         * The adjacency is the guard: "segundo o guia…" and "no primeiro banner…" carry the
         * same words but never this shape, so they stay with the other paths.
         */
        private val ORDINAL_NEXT = setOf(
            "melhor", "melhores", "opcao", "opcoes",
            "cone", "cones", "reliquia", "reliquias", "ornamento", "ornamentos",
        )

        /** Facet words for the group-by cue ("de cada elemento", "por caminho"). */
        private val FACET_WORDS = mapOf(
            "elemento" to Facet.ELEMENTO, "elementos" to Facet.ELEMENTO,
            "element" to Facet.ELEMENTO, "elements" to Facet.ELEMENTO,
            "caminho" to Facet.CAMINHO, "caminhos" to Facet.CAMINHO,
            "path" to Facet.CAMINHO, "paths" to Facet.CAMINHO,
            "raridade" to Facet.RARIDADE, "raridades" to Facet.RARIDADE,
            "faccao" to Facet.FACCAO, "faccoes" to Facet.FACCAO,
            "faction" to Facet.FACCAO, "factions" to Facet.FACCAO,
        )

        /** A facet word only groups when one of these immediately precedes it. */
        private val GROUP_PRE = setOf("cada", "por")

        /** Articles that make the entity word an indefinite subject ("um personagem de gelo"). */
        private val INDEFINITE = setOf("um", "uma", "algum", "alguma", "qualquer")

        /**
         * Parses a plan-shaped question, or null when it isn't one. Requires one of the three
         * NEW shapes — ordinal, group-by, indefinite pick — so everything the flat parsers
         * already answer ([RosterAnswerService], [BuildAnswerService]) is left untouched.
         * [factions] and [namedIds] are pre-resolved by the caller so this stays pure.
         */
        internal fun parse(
            query: String,
            factions: Collection<String> = emptyList(),
            namedIds: List<String> = emptyList(),
        ): QueryPlan? {
            val norm = HsrCharacterService.normalize(query)
            val tokens = norm.split(' ')

            // Shared filter/limit extraction (same vocabulary as the roster parser).
            val rarity = RosterAnswerService.RARITY.find(norm)
            val raridade = rarity?.groupValues?.get(1)?.toInt()
            val rest = rarity?.let { norm.removeRange(it.range) } ?: norm
            val elemento = HsrTaxonomy.elementIn(norm)
            val caminho = HsrTaxonomy.pathIn(norm)
            val faccao = HsrCharacterService.matchLongest(query, factions).firstOrNull()
            val count = tokens.any { it in RosterAnswerService.COUNT_WORDS }
            val random = tokens.any { it in RosterAnswerService.RANDOM_WORDS }
            val number = RosterAnswerService.NUMBER.find(rest)?.groupValues?.get(1)?.toInt()?.coerceIn(1, 50)

            // Ordinal tier: "terceiro melhor cone pro Phainon", "segunda opção de relíquia da Kafka".
            val ordinal = tokens.withIndex().firstNotNullOfOrNull { (i, t) ->
                ORDINAL_WORDS[t]?.takeIf { tokens.getOrNull(i + 1) in ORDINAL_NEXT }
            }
            if (ordinal != null) {
                val entity = tokens.firstNotNullOfOrNull { t ->
                    RosterAnswerService.ENTITY_CUES[t]?.takeIf { it != Entity.PERSONAGEM }
                }
                if (entity != null && namedIds.isNotEmpty()) {
                    return QueryPlan(
                        entity = entity,
                        characterIds = namedIds.take(MAX_CHARACTERS),
                        ordinal = ordinal,
                        project = Project.EFEITO,
                    )
                }
            }

            // Group tier: "5 personagens de cada elemento", "cones por caminho".
            val groupBy = tokens.withIndex().firstNotNullOfOrNull { (i, t) ->
                FACET_WORDS[t]?.takeIf { tokens.getOrNull(i - 1) in GROUP_PRE }
            }
            val named = tokens.mapNotNull { RosterAnswerService.ENTITY_CUES[it] }
            if (groupBy != null) {
                val entity = named.firstOrNull { it != Entity.PERSONAGEM } ?: named.firstOrNull()
                if (entity != null && groupSupported(entity, groupBy)) {
                    // "um personagem de cada elemento" — the indefinite article IS the per-group limit.
                    val one = tokens.withIndex().any { (i, t) ->
                        t in INDEFINITE && RosterAnswerService.ENTITY_CUES.containsKey(tokens.getOrNull(i + 1) ?: "")
                    }
                    return QueryPlan(
                        entity = entity,
                        // The grouped facet's own filter is redundant — grouping shows it anyway.
                        elemento = elemento.takeIf { groupBy != Facet.ELEMENTO },
                        caminho = caminho.takeIf { groupBy != Facet.CAMINHO },
                        raridade = raridade.takeIf { groupBy != Facet.RARIDADE },
                        faccao = faccao.takeIf { groupBy != Facet.FACCAO },
                        groupBy = groupBy,
                        project = if (count) Project.COUNT else Project.LIST,
                        limit = number ?: if (one) 1 else RosterAnswerService.DEFAULT_LIMIT,
                        random = random,
                    )
                }
            }

            // Pick tier: an indefinite article right before an entity word, plus a projection
            // that needs a single subject ("a build de um personagem de gelo").
            val pickEntity = tokens.withIndex().firstNotNullOfOrNull { (i, t) ->
                if (t in INDEFINITE) RosterAnswerService.ENTITY_CUES[tokens.getOrNull(i + 1) ?: ""] else null
            }
            if (pickEntity == Entity.PERSONAGEM) {
                val labels = BuildAnswerService.wantedLabels(query)
                // Without a build facet there is nothing to project — the roster list keeps it.
                if (labels.isNotEmpty()) {
                    return QueryPlan(
                        entity = Entity.PERSONAGEM,
                        elemento = elemento, caminho = caminho, raridade = raridade, faccao = faccao,
                        pick = true, labels = labels, project = Project.BUILD,
                    )
                }
            }
            if (pickEntity == Entity.CONE && (random || "efeito" in tokens)) {
                return QueryPlan(
                    entity = Entity.CONE,
                    caminho = caminho, raridade = raridade,
                    pick = true, project = Project.EFEITO,
                )
            }

            return null
        }

        /** Whether [entity] carries [facet] at all — characters all four, cones two, items none. */
        private fun groupSupported(entity: Entity, facet: Facet): Boolean = when (entity) {
            Entity.PERSONAGEM -> true
            Entity.CONE -> facet == Facet.CAMINHO || facet == Facet.RARIDADE
            else -> false
        }

        /**
         * Cheap gate for the LLM planner tier: worth a planner call only when the question
         * talks about a table at all (an entity word, a count, a group/ordinal cue). Keeps
         * single-entity questions ("quem é a Acheron?") from paying planner latency on their
         * way to retrieval. Pure.
         */
        internal fun looksPlannable(query: String): Boolean {
            val tokens = HsrCharacterService.normalize(query).split(' ')
            return tokens.withIndex().any { (i, t) ->
                t in RosterAnswerService.ENTITY_CUES ||
                    t in RosterAnswerService.COUNT_WORDS ||
                    (t in FACET_WORDS && tokens.getOrNull(i - 1) in GROUP_PRE) ||
                    (t in ORDINAL_WORDS && tokens.getOrNull(i + 1) in ORDINAL_NEXT)
            }
        }

        /**
         * Validates the LLM planner's JSON into a [QueryPlan], or null. STRICT on purpose:
         * a provided field that doesn't resolve (unknown element, unresolvable character,
         * out-of-range ordinal) rejects the WHOLE plan rather than executing a narrower
         * question than the user asked — the caller falls through to retrieval, so a rejected
         * plan costs nothing. [resolveCharacter] is the gazetteer seam, injected so this
         * stays pure and testable.
         */
        internal fun parseLlmPlan(
            raw: String,
            factions: Collection<String>,
            resolveCharacter: (String) -> String?,
        ): QueryPlan? {
            val node = runCatching { JSON.readTree(raw) }.getOrNull() ?: return null
            fun str(field: String): String? = node.get(field)
                ?.takeUnless { it.isNull }?.asText()
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            fun int(field: String): Int? = node.get(field)?.takeUnless { it.isNull }?.takeIf { it.isNumber }?.asInt()

            val entity = when (str("entidade")?.let(HsrCharacterService::normalize)) {
                "personagem", "personagens" -> Entity.PERSONAGEM
                "cone", "cones" -> Entity.CONE
                "reliquia", "reliquias" -> Entity.RELIQUIA
                "ornamento", "ornamentos" -> Entity.ORNAMENTO
                else -> return null
            }
            val elemento = str("elemento")?.let { HsrTaxonomy.elementIn(it) ?: return null }
            val caminho = str("caminho")?.let { HsrTaxonomy.pathIn(it) ?: return null }
            val raridade = int("raridade")?.also { if (it !in 3..5) return null }
            val faccao = str("faccao")?.let { f ->
                HsrCharacterService.matchLongest(f, factions).firstOrNull() ?: return null
            }
            val characterId = str("personagem")?.let { resolveCharacter(it) ?: return null }
            val groupBy = str("agrupar_por")?.let { g ->
                when (HsrCharacterService.normalize(g)) {
                    "elemento" -> Facet.ELEMENTO
                    "caminho" -> Facet.CAMINHO
                    "raridade" -> Facet.RARIDADE
                    "faccao" -> Facet.FACCAO
                    else -> return null
                }
            }
            val ordinal = int("posicao")?.also { if (it !in 1..3) return null }
            val pick = node.get("sortear")?.asBoolean(false) ?: false
            val project = when (str("mostrar")?.let(HsrCharacterService::normalize)) {
                null, "lista", "nomes" -> Project.LIST
                "build" -> Project.BUILD
                "efeito" -> Project.EFEITO
                "contagem", "count" -> Project.COUNT
                else -> return null
            }
            val limit = int("quantidade")?.coerceIn(1, 50)

            // Semantic validation — shapes the executor can't honour are rejected here, once.
            if (ordinal != null && (characterId == null || entity == Entity.PERSONAGEM)) return null
            if (project == Project.EFEITO && ordinal == null && !pick) return null
            if (project == Project.BUILD && characterId == null && !pick) return null
            if (groupBy != null && !groupSupported(entity, groupBy)) return null
            if (entity != Entity.PERSONAGEM && (elemento != null || faccao != null)) return null
            if ((entity == Entity.RELIQUIA || entity == Entity.ORNAMENTO) &&
                (caminho != null || raridade != null)
            ) return null
            val hasFilter = elemento != null || caminho != null || raridade != null || faccao != null
            if (groupBy == null && ordinal == null && !pick && characterId == null &&
                !hasFilter && project != Project.COUNT
            ) return null

            return QueryPlan(
                entity = entity,
                elemento = elemento, caminho = caminho, raridade = raridade, faccao = faccao,
                characterIds = listOfNotNull(characterId),
                groupBy = groupBy,
                ordinal = ordinal,
                pick = pick,
                labels = if (project == Project.BUILD) GameKnowledgeTools.BUILD_LINE_LABELS.toSet() else emptySet(),
                project = if (ordinal != null) Project.EFEITO else project,
                limit = limit ?: RosterAnswerService.DEFAULT_LIMIT,
            )
        }

        // -------------------- renders (pure) -------------------- //

        /** `**{who} — 3º Cone de Luz recomendado**` + the item's name and effect lines. */
        internal fun renderOrdinal(who: String, n: Int, entity: Entity, item: ItemEffect): String {
            val label = when (entity) {
                Entity.CONE -> "Cone de Luz"
                Entity.RELIQUIA -> "Relíquia"
                Entity.ORNAMENTO -> "Ornamento Planar"
                Entity.PERSONAGEM -> entity.label
            }
            return (listOf("**$who — ${n}º $label recomendado**", "**${item.nome}**") +
                item.efeitos.map { "· $it" }).joinToString("\n")
        }

        /** The draw announcement: `Sorteado (Gelo): **Fulano**`. */
        internal fun pickLine(who: String, plan: QueryPlan): String {
            val filters = listOfNotNull(
                plan.raridade?.let { "$it★" }, plan.elemento, plan.caminho, plan.faccao,
            ).joinToString(" · ")
            return "Sorteado" + (if (filters.isEmpty()) "" else " ($filters)") + ": **$who**"
        }

        /** One cone's facts + effect, same line shape as the signature-cone render. */
        internal fun renderCone(c: ConeDeLuz): String {
            val facts = listOfNotNull(c.raridade?.let { "$it★" }, c.caminho).joinToString(" · ")
            val effect = c.efeitoDescricao?.takeIf { it.isNotBlank() }?.let { d ->
                "Efeito" + (c.efeitoNome?.let { " ($it)" } ?: "") + ": $d"
            }
            return listOfNotNull(
                "**${c.nome}**" + facts.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty(),
                effect,
            ).joinToString("\n")
        }

        /**
         * A [RosterAnswerService.RosterAsk] whose grouped facet reads as already-filtered, so
         * [RosterAnswerService.facts] omits it from the per-row facts (the group header shows
         * it). The sentinel values are never compared, only null-checked.
         */
        private fun suppressGrouped(plan: QueryPlan, facet: Facet): RosterAnswerService.RosterAsk =
            plan.toRosterAsk().let {
                when (facet) {
                    Facet.CAMINHO -> it.copy(caminho = it.caminho ?: "·")
                    Facet.RARIDADE -> it.copy(raridade = it.raridade ?: 0)
                    else -> it // facts() never renders elemento/faccao
                }
            }

        /** Cone row facts minus the grouped facet. */
        private fun coneFacts(c: ConeDeLuz, facet: Facet): String? = listOfNotNull(
            c.caminho.takeIf { facet != Facet.CAMINHO },
            c.raridade?.let { "$it★" }.takeIf { facet != Facet.RARIDADE },
        ).joinToString(" · ").ifEmpty { null }

        /**
         * Grouped listing: a title naming the entity + remaining filters, then one block per
         * facet value (sorted) with up to [QueryPlan.limit] rows each — or one count line per
         * group for a COUNT projection. Shuffle happens BEFORE the cap, same as the roster
         * render. Pure.
         */
        internal fun renderGroups(
            plan: QueryPlan,
            groups: Map<String, List<RosterAnswerService.Row>>,
        ): String? {
            if (groups.isEmpty()) return null
            val facet = plan.groupBy ?: return null
            val filters = listOfNotNull(
                plan.raridade?.let { "$it★" }, plan.elemento, plan.caminho, plan.faccao,
            ).joinToString(" · ")
            val title = "**${plan.entity.label}" +
                (if (filters.isEmpty()) "" else " $filters") + " por ${facet.label}**"
            val sections = groups.entries.sortedBy { it.key }.map { (value, rows) ->
                if (plan.project == Project.COUNT) return@map "**$value**: ${rows.size}"
                val ordered = if (plan.random) rows.shuffled() else rows.sortedBy { it.name }
                val shown = ordered.take(plan.limit)
                val head = if (shown.size < rows.size) {
                    "**$value** (${shown.size} de ${rows.size})"
                } else {
                    "**$value** (${rows.size})"
                }
                (listOf(head) + shown.map { r ->
                    "- ${r.name}" + r.facts?.let { " — $it" }.orEmpty()
                }).joinToString("\n")
            }
            val sep = if (plan.project == Project.COUNT) "\n" else "\n\n"
            return (listOf(title) + sections).joinToString(sep)
        }
    }
}
