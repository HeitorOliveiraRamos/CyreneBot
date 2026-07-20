package com.cyrene.knowledge

import com.cyrene.hsr.BuildView
import com.cyrene.hsr.ConeDeLuz
import com.cyrene.hsr.HsrCharacterService
import com.cyrene.hsr.HsrRepository
import com.cyrene.hsr.NamedText
import com.cyrene.hsr.OrnamentoPlano
import com.cyrene.hsr.PersonagemHsr
import com.cyrene.hsr.Reliquia
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.stereotype.Component

/**
 * Renders the vector-store documents from the rich V17 tables ([HsrRepository]) — the offline,
 * table-sourced replacement for the two live-fetch ingestion sources. The metadata shape
 * (`category` + `name`, plus `character_id` for per-character docs) and the per-doc text layout
 * are the same ones [GameKnowledgeTools.lookupHsr] and its prune/join helpers expect, so the
 * semantic-fallback path is unchanged — only its source moved from HTTP + JSON parsing to a DB read.
 *
 * Categories: **profile** / **skill** (5 abilities + memoespírito/euforia) / **eidolon** / **trace**
 * / **build** per character, **relic_set** (cavern + planar), **light_cone**. The `enemy` category
 * is intentionally dropped — V17 has no monster table.
 *
 * Build docs render item names through the SAME strings as the relic_set/light_cone docs (both
 * come from the item tables' `nome`), so [GameKnowledgeTools.effectDocs]'s exact-name join holds.
 */
@Component
class TableKnowledgeSource(
    private val repo: HsrRepository,
    private val characters: HsrCharacterService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Every embeddable document, built from the tables. Empty when the tables are empty. */
    fun load(): List<Document> {
        val docs = mutableListOf<Document>()
        val personagens = repo.personagens()
        personagens.forEach { docs += characterDocs(it) }
        repo.allReliquias().forEach { docs += relicSetDoc(it) }
        repo.allOrnamentos().forEach { docs += ornamentDoc(it) }
        repo.allCones().forEach { docs += coneDoc(it) }
        repo.builds().forEach { buildDoc(it)?.let { d -> docs += d } }
        log.info("TableKnowledge: {} documents from {} personagens", docs.size, personagens.size)
        return docs
    }

    private fun characterDocs(p: PersonagemHsr): List<Document> {
        // Path-disambiguated: `nome` alone is shared by both 7 de Março rows and by the ten
        // Trailblazer rows, so using it as the doc `name` collapsed several characters onto one
        // metadata key and the name-anchored tier returned their docs interleaved.
        val who = characters.displayName(p.characterId)
        val docs = mutableListOf(profileDoc(p, who))
        SKILL_LABELS.forEach { (label, pick) ->
            skillDoc(who, p.characterId, label, pick(p))?.let { docs += it }
        }
        p.eidolons.forEachIndexed { i, eid ->
            abilityText(eid)?.let { docs += doc("$who — Eidolon ${i + 1}: ${eid.nome.orEmpty()}\n$it".trim(), "eidolon", who, p.characterId) }
        }
        p.tracos.forEach { t ->
            abilityText(t)?.let { docs += doc("$who — traço maior (Bonus Ability): ${t.nome.orEmpty()}\n$it".trim(), "trace", who, p.characterId) }
        }
        return docs
    }

    private fun profileDoc(p: PersonagemHsr, who: String): Document {
        val text = buildString {
            append("$who — personagem de Honkai: Star Rail.\n")
            p.raridade?.let { append("Raridade: $it estrelas\n") }
            p.caminho?.let { append("Caminho (Path): $it\n") }
            p.elemento?.let { append("Elemento: $it\n") }
            p.descricao?.takeIf { it.isNotBlank() }?.let { append("Descrição: $it\n") }
        }.trim()
        return doc(text, "profile", who, p.characterId)
    }

    private fun skillDoc(who: String, charId: String, label: String, nt: NamedText): Document? {
        val body = abilityText(nt) ?: return null
        val header = "$who — habilidade: ${nt.nome.orEmpty()} ($label)"
        return doc("$header\n$body".trim(), "skill", who, charId)
    }

    private fun relicSetDoc(r: Reliquia): Document {
        val text = buildString {
            append("${r.nome} — Conjunto de Relíquia (Cavern Relics) de Honkai: Star Rail.\n")
            r.efeito2Pecas?.let { append("Bônus 2 peças: $it\n") }
            r.efeito4Pecas?.let { append("Bônus 4 peças: $it\n") }
        }.trim()
        return doc(text, "relic_set", r.nome)
    }

    private fun ornamentDoc(o: OrnamentoPlano): Document {
        val text = buildString {
            append("${o.nome} — Ornamento Planar (Planar Ornament) de Honkai: Star Rail.\n")
            o.efeito2Pecas?.let { append("Bônus 2 peças: $it\n") }
        }.trim()
        return doc(text, "relic_set", o.nome)
    }

    private fun coneDoc(c: ConeDeLuz): Document {
        val text = buildString {
            append("${c.nome} — Cone de Luz (Light Cone) de Honkai: Star Rail.\n")
            c.raridade?.let { append("Raridade: $it estrelas\n") }
            c.caminho?.let { append("Caminho (Path): $it\n") }
            if (c.efeitoNome != null || c.efeitoDescricao != null) {
                append("Efeito${c.efeitoNome?.let { " ($it)" } ?: ""}: ${c.efeitoDescricao.orEmpty()}\n")
            }
        }.trim()
        return doc(text, "light_cone", c.nome)
    }

    /** Same labeled-line shape as the old nanoka build doc — item names "; "-joined, one item per set. */
    private fun buildDoc(b: BuildView): Document? {
        if (b.reliquias.isEmpty() && b.cones.isEmpty()) return null
        val who = characters.displayName(b.characterId)
        val text = buildString {
            append("$who — build recomendada (Honkai: Star Rail).\n")
            names(b.reliquias)?.let { append("Relíquias (4 peças, melhor primeiro): $it\n") }
            names(b.ornamentos)?.let { append("Ornamento Planar (melhor primeiro): $it\n") }
            names(b.cones)?.let { append("Cone de Luz (melhor primeiro): $it\n") }
            mainStats(b)?.let { append("Main stats: $it\n") }
            b.substatusRecomendados?.let { append("Substats (prioridade): $it\n") }
            b.equipeRecomendada?.let { append("Equipe recomendada: $it\n") }
        }.trim()
        return doc(text, "build", who, b.characterId)
    }

    private companion object {
        /** Ability field → PT header label, in the doc's canonical order. */
        private val SKILL_LABELS: List<Pair<String, (PersonagemHsr) -> NamedText>> = listOf(
            "ATQ Básico" to { p: PersonagemHsr -> p.atqBasico },
            "Perícia" to { p -> p.pericia },
            "Perícia Suprema" to { p -> p.periciaSuprema },
            "Talento" to { p -> p.talento },
            "Técnica" to { p -> p.tecnica },
            "Perícia do Memoespírito" to { p -> p.periciaMemoespirito },
            "Talento do Memoespírito" to { p -> p.talentoMemoespirito },
            "Perícia da Euforia" to { p -> p.periciaEuforia },
        )

        /** Description text of an ability/eidolon/trace, or null when the pair carries none. */
        private fun abilityText(nt: NamedText): String? =
            if (nt.isBlank) null else nt.descricao?.takeIf { it.isNotBlank() } ?: nt.nome?.takeIf { it.isNotBlank() }

        private fun names(items: List<com.cyrene.hsr.ItemEffect>): String? =
            items.map { it.nome }.takeIf { it.isNotEmpty() }?.joinToString("; ")

        private fun mainStats(b: BuildView): String? = listOfNotNull(
            b.mainStatCorpo?.let { "Corpo: $it" },
            b.mainStatPes?.let { "Pés: $it" },
            b.mainStatEsfera?.let { "Esfera Planar: $it" },
            b.mainStatCorda?.let { "Corda de Conexão: $it" },
        ).takeIf { it.isNotEmpty() }?.joinToString(", ")

        private fun doc(text: String, category: String, name: String, characterId: String? = null): Document {
            val meta = buildMap<String, Any> {
                put("category", category)
                put("name", name)
                characterId?.let { put("character_id", it) }
            }
            return Document(text, meta)
        }
    }
}
