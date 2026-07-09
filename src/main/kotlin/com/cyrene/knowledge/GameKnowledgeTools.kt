package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import com.cyrene.hsr.HsrCharacterService
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Tools the LLM can invoke to ground its answers about Honkai: Star Rail in real data
 * instead of hallucinating character names, stats, or mechanics.
 *
 * Two-tier strategy, mirroring the safety-first philosophy of
 * [com.cyrene.discord.tools.DiscordTools]:
 *  1. [lookupHsr] — searches the local vector store (Kaggle datasets + scraped wiki,
 *     ingested by [HsrKnowledgeIngestion]). Fast, offline, authoritative for static data.
 *  2. [searchWeb] — online fallback via [WebSearchClient], used ONLY when the local base
 *     has nothing relevant (new patch, just-released unit, live event).
 *
 * Both return plain `Map`s; Spring AI serializes them to JSON for the model. Neither tool
 * mutates anything, so unlike the moderation tools there are no authority checks — the
 * worst case is an irrelevant snippet, which the brain pass is told to ignore.
 */
@Component
class GameKnowledgeTools(
    private val vectorStore: VectorStore,
    private val webSearch: WebSearchClient,
    private val properties: BotProperties,
    private val jdbc: JdbcTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(
        description = "Consulta a base de conhecimento LOCAL de Honkai: Star Rail (personagens, " +
            "caminhos/Paths, Eidolons, cones de luz/Light Cones, relíquias, mecânicas, lore). " +
            "Use SEMPRE ANTES de afirmar qualquer fato sobre o jogo — nomes, status, kits, builds — " +
            "para não inventar. Equivalentes em PT-BR: 'quem é', 'o que faz', 'qual o kit', 'build de', " +
            "'melhor relíquia para', 'qual caminho', 'qual elemento'. Se esta ferramenta não retornar " +
            "nada relevante, então use searchWeb.",
    )
    fun lookupHsr(
        @ToolParam(description = "A pergunta ou termo a buscar, em linguagem natural. Ex.: 'kit e relíquias da Acheron', 'elemento do Jing Yuan'.")
        query: String,
    ): Map<String, Any?> {
        val k = properties.knowledge

        // Name-anchored tier: when the query literally names a KB entity (character, light
        // cone, relic set, enemy — whatever carries metadata->>'name'), fetch that entity's
        // docs directly. Cosine similarity is weak on proper nouns — exact whole-word name
        // matching is how "qual o passivo do cone Along the Passing Shore?" retrieves the
        // right doc even when the embedding ranks it below the threshold.
        val nameHits: List<Map<String, Any?>> = try {
            nameAnchoredDocs(query)
        } catch (e: Exception) {
            log.warn("lookupHsr name-anchored search failed for '{}': {}", query, e.message)
            emptyList()
        }

        val request = SearchRequest.builder()
            .query(query)
            .topK(k.topK)
            .similarityThreshold(k.similarityThreshold)
            .build()

        val docs: List<Document> = try {
            vectorStore.similaritySearch(request) ?: emptyList()
        } catch (e: Exception) {
            log.error("lookupHsr vector search failed for '{}'", query, e)
            if (nameHits.isEmpty()) {
                return mapOf("found" to false, "error" to "Knowledge base unavailable: ${e.message}")
            }
            emptyList()
        }

        // Name hits lead (exact-entity precision); vector hits fill in. When the query named an
        // entity, mergeHits keeps the name tier authoritative — the vector fill is scoped to the
        // SAME entities and the same kit section. Without that scope, semantic search drags in
        // every other character's near-identical doc: build docs all read "build recomendada…
        // Relíquias… Ornamento…", so "qual set da Cyrene" pulled in 4 unrelated builds. No name
        // match → pure vector, exactly as before. Anchored docs are never truncated to topK
        // (an entity's docs are stored profile→skills→eidolons; a flat cap dropped the tail).
        val vectorHits = docs.map { doc ->
            mapOf(
                "content" to doc.text,
                "category" to (doc.metadata["category"] ?: "unknown"),
                "name" to doc.metadata["name"],
            )
        }
        val cap = if (nameHits.isEmpty()) k.topK + 3 else MAX_ANCHORED + k.topK
        val merged = mergeHits(nameHits, vectorHits, query).take(cap)
        // Build docs are trimmed to the line(s) the question names (team, relics, cone,
        // stats...) BEFORE anything downstream sees them: handed the whole doc, the voice
        // model remixed the leftovers — "equipe recomendada do welt" came back with Welt's
        // own relics dealt out across the teammates as invented per-character builds.
        val pruned = pruneBuildLines(merged, query)
        // A build doc lists relic/ornament/cone NAMES only; join in each item's own
        // relic_set/light_cone doc so the voice pass retells the real effect text
        // instead of inventing one per name. Appended after the cap: at most ~9 short
        // docs per build, never displacing the anchored kit. Runs on the PRUNED lines,
        // which scopes it for free: a team/stat question keeps no item lines and joins
        // nothing (their numbers used to leak into stat answers), a relic-only question
        // joins relic docs but not cones.
        val results = pruned + effectDocs(pruned)

        if (results.isEmpty()) {
            log.debug("lookupHsr: no local hit for '{}' (threshold {})", query, k.similarityThreshold)
            return mapOf(
                "found" to false,
                "note" to "Nada relevante na base local. Considere chamar searchWeb para informação online/recente.",
            )
        }

        log.debug("lookupHsr: {} hits for '{}' ({} name-anchored)", results.size, query, nameHits.size)
        return mapOf(
            "found" to true,
            "source" to "local_knowledge_base",
            "results" to results,
        )
    }

    /**
     * Docs whose metadata name appears whole-word in the query. The DISTINCT name scan is a
     * sub-ms seq read over ~1k rows — no index or cache needed at this KB size.
     */
    private fun nameAnchoredDocs(query: String): List<Map<String, Any?>> {
        val names = jdbc.queryForList(
            "SELECT DISTINCT metadata->>'name' FROM vector_store WHERE metadata->>'name' IS NOT NULL",
            String::class.java,
        )
        val matched = matchNames(query, names)
        if (matched.isEmpty()) return emptyList()
        val placeholders = matched.joinToString(",") { "?" }
        return jdbc.queryForList(
            "SELECT content, metadata->>'category' AS category, metadata->>'name' AS name " +
                "FROM vector_store WHERE metadata->>'name' IN ($placeholders)",
            *matched.toTypedArray(),
        ).map { row ->
            mapOf<String, Any?>(
                "content" to row["content"],
                "category" to (row["category"] ?: "unknown"),
                "name" to row["name"],
            )
        }
    }

    /**
     * relic_set / light_cone docs for the items named inside retrieved build docs, skipping
     * any already present in [results]. Exact-name lookup: build docs and item docs are
     * rendered from the same nanoka index files, so the names match verbatim. Fail-open.
     */
    private fun effectDocs(results: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val wanted = results.filter { it["category"] == "build" }
            .flatMap { buildItemNames(it["content"] as? String ?: "") }
            .distinct()
            .minus(results.mapNotNull { it["name"] as? String }.toSet())
        if (wanted.isEmpty()) return emptyList()
        val placeholders = wanted.joinToString(",") { "?" }
        return try {
            jdbc.queryForList(
                "SELECT content, metadata->>'category' AS category, metadata->>'name' AS name " +
                    "FROM vector_store WHERE metadata->>'category' IN ('relic_set','light_cone') " +
                    "AND metadata->>'name' IN ($placeholders)",
                *wanted.toTypedArray(),
            ).map { row ->
                mapOf<String, Any?>(
                    "content" to row["content"],
                    "category" to (row["category"] ?: "unknown"),
                    "name" to row["name"],
                )
            }
        } catch (e: Exception) {
            log.warn("lookupHsr effect join failed: {}", e.message)
            emptyList()
        }
    }

    @Tool(
        description = "Pesquisa Honkai: Star Rail NA INTERNET e LÊ o conteúdo das páginas (não só " +
            "títulos). Use quando: (a) lookupHsr não encontrou na base local; (b) a pergunta é sobre " +
            "conteúdo NOVO/recente ou ainda não lançado — personagens recém-lançados ou VAZADOS/leaks, " +
            "kits futuros, patch/versão atual, banners, eventos; ou (c) o usuário pediu EXPLICITAMENTE " +
            "para pesquisar na internet/web/online (nesse caso chame mesmo que lookupHsr tenha achado " +
            "algo, e combine as duas fontes). Cada resultado traz 'content' com o texto da página — use-o, " +
            "não só o 'snippet'.",
    )
    fun searchWeb(
        @ToolParam(description = "O que buscar na internet, em linguagem natural. Ex.: 'banner atual versão 3.3', 'novo personagem 5 estrelas'.")
        query: String,
    ): Map<String, Any?> = searchWeb(query, recent = false)

    /**
     * [recent] variant for the deterministic grounder: news-shaped questions pass `true`,
     * which makes [WebSearchClient] filter to the last month and read one extra page. Kept
     * out of the @Tool signature so the model never sees (or hallucinates) the flag.
     */
    fun searchWeb(query: String, recent: Boolean): Map<String, Any?> {
        if (!webSearch.isEnabled) {
            return mapOf(
                "found" to false,
                "note" to "Busca na web não está configurada (sem SEARXNG_URL). Responda apenas com " +
                    "o que a base local fornece e, se não houver, admita que não tem a informação atualizada.",
            )
        }

        val results = webSearch.search(query, limit = properties.knowledge.topK, recent = recent)
        if (results.isEmpty()) {
            return mapOf("found" to false, "note" to "Nenhum resultado online encontrado para a busca.")
        }

        return mapOf(
            "found" to true,
            "source" to "web_search",
            // `content` is the full page text for the top results (empty for the rest, or when
            // the fetch failed). Prefer `content` over `snippet` when present — the snippet is
            // only a teaser; the kit details live in `content`.
            "results" to results.map {
                mapOf(
                    "title" to it.title,
                    "url" to it.url,
                    "snippet" to it.snippet,
                    "content" to it.content,
                )
            },
        )
    }

    internal companion object {
        /** Ceiling on anchored docs so a 4-name listy question can't blow the 16k context. */
        // ponytail: flat cap; per-entity budgeting if multi-character questions get truncated.
        private const val MAX_ANCHORED = 30

        private val TOKEN_SEP = Regex("[^\\p{L}\\p{N}]+")

        /** "e2" / "eidolon 2" in a normalized query → the eidolon number asked about. */
        private val EIDOLON_NUM = Regex("\\b(?:e|eidolon\\s*)([1-6])\\b")

        /** Build-doc lines that list equipment by name (labels from NanokaIngestionSource.buildDoc). */
        private val ITEM_LINES = listOf("Relíquias", "Ornamento Planar", "Cone de Luz")

        /**
         * Build-doc line labels (from [NanokaIngestionSource.buildDoc]) keyed by the
         * normalized facet words that ask for them. Slot words (esfera/corda/bola) keep
         * the ornament line AND the stat lines — the question may mean either. Words that
         * cue nothing here ("build", "efeito", "quem é") leave the doc whole.
         */
        private val LINE_CUES: Map<String, Set<String>> = listOf(
            setOf("Equipe recomendada") to "equipe equipes team teams time times",
            setOf("Relíquias") to "reliquia reliquias relic relics set sets",
            setOf("Ornamento Planar") to "ornamento ornamentos ornament ornaments",
            setOf("Ornamento Planar", "Main stats", "Substats") to "esfera corda bola",
            setOf("Cone de Luz") to "cone cones lightcone lightcones",
            setOf("Main stats", "Substats") to "main stat stats mainstat mainstats " +
                "substat substats corpo pes botas",
        ).flatMap { (labels, words) -> words.split(' ').map { it to labels } }.toMap()

        /**
         * Trims each build doc to the line(s) the query asks about; the title line always
         * survives. The whole doc handed to a small voice model is raw material for
         * recombination (Welt's relics dealt out across his teammates), so the lines the
         * question didn't ask for must not reach it. No facet word — or a facet this doc
         * has no line for — leaves the doc whole, mirroring [filterBySection]: pruning
         * only ever raises precision, never causes a miss. Non-build docs pass through.
         * Pure, so the line-scoping contract is unit-testable without a vector store.
         */
        internal fun pruneBuildLines(
            docs: List<Map<String, Any?>>,
            query: String,
        ): List<Map<String, Any?>> {
            val q = HsrCharacterService.normalize(query)
            val labels = q.split(TOKEN_SEP).flatMap { LINE_CUES[it].orEmpty() }.toSet()
            if (labels.isEmpty()) return docs
            return docs.map { doc ->
                val content = doc["content"] as? String
                if (doc["category"] != "build" || content == null) return@map doc
                val kept = content.lines()
                    .filterIndexed { i, line -> i == 0 || labels.any(line::startsWith) }
                if (kept.size <= 1) doc else doc + ("content" to kept.joinToString("\n"))
            }
        }

        /**
         * Equipment names listed in a build doc ("Relíquias …: A; B; C") — the items whose
         * effect text lives in their own relic_set/light_cone docs. Team/stat lines are
         * skipped. Pure, so the parsing contract is unit-testable without a DB.
         */
        internal fun buildItemNames(buildContent: String): List<String> =
            buildContent.lineSequence()
                .filter { line -> ITEM_LINES.any(line::startsWith) }
                .flatMap { it.substringAfter(':').split(';') }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()

        /**
         * Combines the exact name-anchored hits with the semantic vector fill. With no named
         * entity the vector tier stands alone (deduped) — it's all we have. With a named entity
         * the name tier is authoritative: the vector fill is scoped to the SAME entity names and
         * then run through [filterBySection], so it can only add more chunks of what the user
         * asked about, never a different character's look-alike doc. Pure, so this whole
         * merge/scope contract is unit-testable without a vector store. Order (name hits first)
         * is preserved, and [distinctBy] lets a name hit win over a duplicate vector hit.
         */
        internal fun mergeHits(
            nameHits: List<Map<String, Any?>>,
            vectorHits: List<Map<String, Any?>>,
            query: String,
        ): List<Map<String, Any?>> {
            if (nameHits.isEmpty()) return vectorHits.distinctBy { it["content"] }
            val names = nameHits.mapNotNull { it["name"] as? String }.toSet()
            val scoped = vectorHits.filter { (it["name"] as? String) in names }
            return filterBySection(nameHits + scoped, query).distinctBy { it["content"] }
        }

        /**
         * Normalized (accent-stripped) query tokens → the vector_store categories they cue.
         * "kit" spans the whole character kit; stat words ("elemento") pin the profile doc.
         */
        private val SECTION_CUES: Map<String, Set<String>> = listOf(
            setOf("eidolon") to "eidolon eidolons",
            setOf("skill") to "habilidade habilidades skill skills ultimate ultimato ult ulti " +
                "talento talentos talent tecnica tecnicas technique basico basica ataque ataques",
            setOf("trace") to "traco tracos trace traces passiva passivas passivo passivos",
            // relic/cone words also pin a CHARACTER's build doc ("melhor cone pra acheron"):
            // whichever category the anchored entity actually has is the one that survives.
            setOf("light_cone", "build") to "cone cones lightcone lightcones",
            setOf("relic_set", "build") to "reliquia reliquias relic relics set sets " +
                "ornamento ornamentos ornament ornaments",
            // "main stat" arrives as TWO tokens — both must cue (live miss: "qual main stat no
            // corpo do phainon" matched nothing and shipped the whole kit to the voice pass).
            setOf("build") to "build builds time times equipe equipes team teams " +
                "substat substats mainstat mainstats main stat stats corpo pes botas",
            // Ornament slot words: build doc for "qual esfera pra acheron", the set's own doc
            // for "efeito da esfera arena rutilante" — whichever the anchored entity has.
            setOf("relic_set", "build") to "esfera corda",
            setOf("profile") to "elemento caminho path raridade",
            setOf("profile", "skill", "trace", "eidolon") to "kit",
        ).flatMap { (cats, words) -> words.split(' ').map { it to cats } }.toMap()

        /**
         * Narrows name-anchored [docs] to the kit section(s) the [query] asks about
         * ("eidolon 2", "ultimate", "traço", "cone"...). No cue — or a cue that matches
         * nothing this entity has — leaves the docs untouched, so filtering only ever raises
         * precision, never causes a miss. An "e2"/"eidolon 2" query additionally narrows to
         * that single eidolon's doc. Pure, so it's unit-testable without a vector store.
         */
        internal fun filterBySection(
            docs: List<Map<String, Any?>>,
            query: String,
        ): List<Map<String, Any?>> {
            if (docs.isEmpty()) return docs
            val q = HsrCharacterService.normalize(query)
            val eidolonNum = EIDOLON_NUM.find(q)?.groupValues?.get(1)
            val cued = q.split(TOKEN_SEP).flatMap { SECTION_CUES[it].orEmpty() }.toMutableSet()
            if (eidolonNum != null) cued += "eidolon"
            if (cued.isEmpty()) return docs
            val section = docs.filter { it["category"] in cued }.ifEmpty { return docs }
            if (eidolonNum == null) return section
            return section.filter {
                it["category"] != "eidolon" ||
                    (it["content"] as? String)?.contains("Eidolon $eidolonNum") == true
            }.ifEmpty { section }
        }

        /**
         * Entity names present whole-word in the query (accent/case-insensitive), same
         * matching contract as the character gazetteer: full name, ≥4 normalized chars — no
         * substring hits, no advice from one common token of a multi-word name. Capped at 4
         * names so a listy question can't pull half the KB into the context. Pure.
         */
        internal fun matchNames(query: String, names: Collection<String?>): List<String> {
            val q = HsrCharacterService.normalize(query)
            if (q.isEmpty()) return emptyList()
            return names.asSequence()
                .filterNotNull()
                .distinct()
                .filter { name ->
                    val n = HsrCharacterService.normalize(name)
                    n.length >= 4 && HsrCharacterService.containsWord(q, n)
                }
                .take(4)
                .toList()
        }
    }
}
