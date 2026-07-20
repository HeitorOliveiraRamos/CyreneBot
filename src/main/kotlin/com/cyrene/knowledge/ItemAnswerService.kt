package com.cyrene.knowledge

import com.cyrene.hsr.HsrCharacterService
import com.cyrene.hsr.HsrRepository
import com.cyrene.hsr.ItemEffect
import com.cyrene.hsr.NamedText
import com.cyrene.hsr.OrnamentoPlano
import com.cyrene.hsr.Reliquia
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Deterministic, LLM-free answers for item-effect questions — "o que faz o cone Along the
 * Passing Shore?", "qual o bônus de 2 peças da Arena Rutilante?", "efeito do ornamento Izumo?".
 *
 * Seam 2: the effect is read straight from the `reliquias`/`ornamentos_planos`/`cones_de_luz`
 * rows ([HsrRepository]) whose `nome` the query names verbatim. The item-name pool comes from
 * those same tables, so this path can only ever answer about actual items — a character name
 * matches no item row and returns null, leaving character questions to their build/kit routes.
 * It also runs AFTER those two in [com.cyrene.ai.OllamaAiService], so "melhor cone pra acheron"
 * is theirs before this service ever sees it.
 */
@Component
class ItemAnswerService(
    private val repo: HsrRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun answer(query: String): String? {
        val fields = wantedPieces(query)
        // The name scan costs a query, so the cue gate stays in front of it.
        if (!wantsItem(query) && fields.isEmpty()) return null
        val itemNames = repo.itemNames()
        // Same exact-then-fuzzy tiering as the character gazetteer: relic and cone names are long
        // and easy to half-remember ("izumo" for "Fábrica Sagrada de Izumo"). The FUZZY tier gets
        // the cue words stripped: item names are ordinary Portuguese, so the cone "A Próxima
        // Página da História" owns "história" as a distinctive token and hijacked every character
        // lore question. The EXACT tier still sees the raw query, so asking for that cone by its
        // real name — cue word and all — keeps working.
        val names = HsrCharacterService.matchLongest(query, itemNames)
            .ifEmpty { HsrCharacterService.fuzzyMatch(nameScope(query), itemNames) }
            .take(MAX_ITEMS)
        if (names.isEmpty()) return null

        // Field projection first: "qual o nome dos pés do set Campeã de Boxe de Rua" wants that one
        // piece, not the set's 2pc/4pc bonuses. Falls through to the effect render when the named
        // items have nothing for the asked piece (a cone has no "pés").
        if (fields.isNotEmpty()) {
            val pieces = renderPieces(names, fields)
            if (pieces != null) {
                log.debug("Deterministic item-field answer for '{}' (fields {})", query, fields)
                return pieces
            }
        }

        if (!wantsItem(query)) return null
        val items = repo.itemsByName(names)
        if (items.isEmpty()) return null
        log.debug("Deterministic item answer for '{}' ({} items)", query, items.size)
        return items.joinToString("\n\n") { rendered(it) }
    }

    /** Blocks for the asked piece(s) of the named items; null when none of them carry any. */
    private fun renderPieces(names: List<String>, fields: Set<PieceField>): String? {
        val blocks = mutableListOf<String>()
        repo.reliquiasByName(names).forEach { r ->
            fields.forEach { f -> f.ofRelic(r)?.let { blocks += pieceBlock(r.nome, f, it) } }
        }
        repo.ornamentosByName(names).forEach { o ->
            fields.forEach { f -> f.ofOrnament(o)?.let { blocks += pieceBlock(o.nome, f, it) } }
        }
        if (PieceField.LORE in fields) {
            repo.conesByName(names).forEach { c ->
                c.descricao?.takeIf { it.isNotBlank() }?.let { blocks += "**${c.nome} — Descrição**\n$it" }
            }
        }
        return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * One physical piece of a relic/ornament set, or a cone's lore blurb — the columns
     * [HsrRepository]'s effect views drop. [ofRelic]/[ofOrnament] return null for a piece the
     * table doesn't have (a relic set has no Esfera; an ornament has no Botas).
     */
    internal enum class PieceField(val tag: String) {
        CABECA("Cabeça"), MAOS("Mãos"), CORPO("Corpo"), PES("Pés"),
        ESFERA("Esfera Planar"), CORDA("Corda de Conexão"), LORE("Descrição");

        fun ofRelic(r: Reliquia): NamedText? = when (this) {
            CABECA -> r.cabeca
            MAOS -> r.maos
            CORPO -> r.corpo
            PES -> r.pes
            else -> null
        }?.takeUnless { it.isBlank }

        fun ofOrnament(o: OrnamentoPlano): NamedText? = when (this) {
            ESFERA -> o.esfera
            CORDA -> o.corda
            else -> null
        }?.takeUnless { it.isBlank }
    }

    internal companion object {
        private const val MAX_ITEMS = 4

        /**
         * Player vocabulary → piece. Hand-mapped for the same reason the character fields are:
         * a closed, tiny column set. "bola" and "botas" are in because that's what players
         * actually type for Esfera and Pés.
         */
        // ponytail: hand-kept alias map; add a line when a real question misses.
        private val PIECE_CUES: Map<String, PieceField> = listOf(
            PieceField.CABECA to "cabeca cabecas capacete elmo head",
            PieceField.MAOS to "maos mao luvas luva hands",
            PieceField.CORPO to "corpo armadura body",
            PieceField.PES to "pes botas bota sapatos feet",
            PieceField.ESFERA to "esfera esferas bola orb sphere",
            PieceField.CORDA to "corda cordas rope",
            PieceField.LORE to "descricao lore historia",
        ).flatMap { (field, words) -> words.split(' ').map { it to field } }.toMap()

        /** The piece(s)/lore the query asks for, over normalized tokens. Pure. */
        internal fun wantedPieces(query: String): Set<PieceField> =
            HsrCharacterService.normalize(query).split(' ').mapNotNull { PIECE_CUES[it] }.toSet()

        /**
         * The query with the cue vocabulary removed, for FUZZY name matching only. A word that
         * asked for a field ("história", "descrição", "pés") is never part of the name of the item
         * being asked about, but it very often IS a word inside some other item's name. Pure.
         */
        internal fun nameScope(query: String): String =
            HsrCharacterService.normalize(query).split(' ')
                .filterNot { it in PIECE_CUES || it in ITEM_CUES }
                .joinToString(" ")

        /** `**{set} — {piece}: {nome}**` + the piece's flavour text. Pure. */
        internal fun pieceBlock(set: String, field: PieceField, nt: NamedText): String {
            val header = "**$set — ${field.tag}" + (nt.nome?.let { ": $it" } ?: "") + "**"
            return nt.descricao?.takeIf { it.isNotBlank() }?.let { "$header\n$it" } ?: header
        }

        /**
         * Effect-ask vocabulary over normalized tokens. Comparison/judgment words ("melhor",
         * "vale") are deliberately absent — those want an opinion, which is the voice path's
         * job; this path only ever states what an item does.
         */
        private val ITEM_CUES = setOf(
            "efeito", "efeitos", "effect", "effects",
            "passiva", "passivas", "passivo", "passivos", "passive",
            "bonus", "peca", "pecas",
        )

        /**
         * True when the query asks what an item does. Bare "faz" is too broad ("o cone X faz
         * sentido?" is a judgment question), so it only cues as the "que faz" bigram —
         * same phrase-over-token trick as [KitAnswerService]'s "perícia suprema". Pure.
         */
        internal fun wantsItem(query: String): Boolean {
            val tokens = HsrCharacterService.normalize(query).split(' ')
            if (tokens.any { it in ITEM_CUES }) return true
            return tokens.zipWithNext().any { (a, b) -> a == "que" && b == "faz" }
        }

        /** `**{nome} — {kind}**` + the effect lines as stored. Pure. */
        internal fun rendered(item: ItemEffect): String {
            val header = "**${item.nome} — ${item.kind}**"
            return if (item.efeitos.isEmpty()) header else "$header\n${item.efeitos.joinToString("\n")}"
        }
    }
}
