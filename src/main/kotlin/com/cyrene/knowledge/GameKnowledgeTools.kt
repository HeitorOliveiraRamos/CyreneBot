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

        // Name hits lead (exact-entity precision), vector hits fill in; dedupe by content.
        val results = (nameHits + docs.map { doc ->
            mapOf(
                "content" to doc.text,
                "category" to (doc.metadata["category"] ?: "unknown"),
                "name" to doc.metadata["name"],
            )
        }).distinctBy { it["content"] }.take(k.topK + 3)

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
    ): Map<String, Any?> {
        if (!webSearch.isEnabled) {
            return mapOf(
                "found" to false,
                "note" to "Busca na web não está configurada (sem SEARXNG_URL). Responda apenas com " +
                    "o que a base local fornece e, se não houver, admita que não tem a informação atualizada.",
            )
        }

        val results = webSearch.search(query, limit = properties.knowledge.topK)
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
