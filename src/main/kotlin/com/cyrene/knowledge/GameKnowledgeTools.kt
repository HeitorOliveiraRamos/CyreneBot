package com.cyrene.knowledge

import com.cyrene.config.BotProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
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

        val request = SearchRequest.builder()
            .query(query)
            .topK(k.topK)
            .similarityThreshold(k.similarityThreshold)
            .build()

        val docs: List<Document> = try {
            vectorStore.similaritySearch(request) ?: emptyList()
        } catch (e: Exception) {
            log.error("lookupHsr vector search failed for '{}'", query, e)
            return mapOf("found" to false, "error" to "Knowledge base unavailable: ${e.message}")
        }

        if (docs.isEmpty()) {
            log.debug("lookupHsr: no local hit for '{}' (threshold {})", query, k.similarityThreshold)
            return mapOf(
                "found" to false,
                "note" to "Nada relevante na base local. Considere chamar searchWeb para informação online/recente.",
            )
        }

        log.debug("lookupHsr: {} hits for '{}'", docs.size, query)
        return mapOf(
            "found" to true,
            "source" to "local_knowledge_base",
            "results" to docs.map { doc ->
                mapOf(
                    "content" to doc.text,
                    "category" to (doc.metadata["category"] ?: "unknown"),
                    "name" to doc.metadata["name"],
                )
            },
        )
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
}
