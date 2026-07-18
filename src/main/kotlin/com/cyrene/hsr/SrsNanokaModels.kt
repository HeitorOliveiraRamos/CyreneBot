package com.cyrene.hsr

/**
 * Domain rows for the richer SRS + nanoka schema (`personagem_hsr` / `reliquias` /
 * `ornamentos_planos` / `cones_de_luz`, migration V17) — the eventual replacement for the
 * V16 [HsrCharacter] cache. Unlike that one's joined `"Nome\ndescrição"` blobs, every ability,
 * eidolon and trace is a name/description pair ([NamedText]), and this carries relic/ornament
 * pieces, memosprite + euphoria abilities, and light-cone lore.
 *
 * Sourced PT-first from starrailstation, falling back to nanoka's English for betas srs hasn't
 * published — so every text field is nullable (a source may lack a given piece). Produced by
 * [SrsNanokaHarvester], stored by [SrsNanokaPopulator]. Kept separate from [HsrCharacter] so the
 * live bot's read paths stay untouched until they're switched over.
 */

/** A name + description pair, mapping one `_nome` / `_descricao` column pair. */
data class NamedText(val nome: String? = null, val descricao: String? = null) {
    val isBlank: Boolean get() = nome.isNullOrBlank() && descricao.isNullOrBlank()

    companion object {
        val EMPTY = NamedText()
    }
}

/**
 * One `personagem_hsr` row. [characterId] is the shared game id (nanoka index key == srs
 * `rankKey`, e.g. "1402"), stored in the `character_id` column; the table's own
 * `id_personagem_hsr` serial is assigned by the DB. [periciaMemoespirito]/[talentoMemoespirito]
 * are non-blank only for Recordação (Remembrance) units; [periciaEuforia] only for Euforia
 * (Elation) units. [tracos] holds A2/A4/A6 in order (≤3); [eidolons] holds E1..E6 in order (≤6);
 * [historias] holds partes 1..4 in order.
 */
data class PersonagemHsr(
    val characterId: String,
    val nome: String? = null,
    val nomeEn: String? = null,
    val elemento: String? = null,
    val caminho: String? = null,
    val raridade: Int? = null,
    val faccao: String? = null,
    val descricao: String? = null,
    val atqBasico: NamedText = NamedText.EMPTY,
    val pericia: NamedText = NamedText.EMPTY,
    val periciaSuprema: NamedText = NamedText.EMPTY,
    val talento: NamedText = NamedText.EMPTY,
    val tecnica: NamedText = NamedText.EMPTY,
    val periciaMemoespirito: NamedText = NamedText.EMPTY,
    val talentoMemoespirito: NamedText = NamedText.EMPTY,
    val periciaEuforia: NamedText = NamedText.EMPTY,
    val tracos: List<NamedText> = emptyList(),
    val eidolons: List<NamedText> = emptyList(),
    val detalhesPersonagem: String? = null,
    val historias: List<String?> = emptyList(),
)

/** One `reliquias` row: a Cavern set's 2/4-piece bonuses plus its four pieces (Cabeça…Pés). */
data class Reliquia(
    val nome: String,
    val efeito2Pecas: String? = null,
    val efeito4Pecas: String? = null,
    val cabeca: NamedText = NamedText.EMPTY,
    val maos: NamedText = NamedText.EMPTY,
    val corpo: NamedText = NamedText.EMPTY,
    val pes: NamedText = NamedText.EMPTY,
)

/** One `ornamentos_planos` row: a Planar set's 2-piece bonus plus its Sphere and Rope. */
data class OrnamentoPlano(
    val nome: String,
    val efeito2Pecas: String? = null,
    val esfera: NamedText = NamedText.EMPTY,
    val corda: NamedText = NamedText.EMPTY,
)

/**
 * One `cones_de_luz` row. [coneGameId] is transient (the shared game cone id) — carried only in
 * memory to resolve the signature link, never a column. `id_personagem_hsr_atribuido` is set
 * afterward from the signature map, not here.
 */
data class ConeDeLuz(
    val coneGameId: String,
    val nome: String,
    val caminho: String? = null,
    val raridade: Int? = null,
    val efeitoNome: String? = null,
    val efeitoDescricao: String? = null,
    val descricao: String? = null,
)

/**
 * A full harvest. [signatureLinks] maps a cone game id → the character game id it's the signature
 * cone for (each character's #1 recommended cone), resolved into
 * `cones_de_luz.id_personagem_hsr_atribuido` after both tables are written.
 */
data class SrsNanokaData(
    val personagens: List<PersonagemHsr>,
    val reliquias: List<Reliquia>,
    val ornamentos: List<OrnamentoPlano>,
    val cones: List<ConeDeLuz>,
    val signatureLinks: Map<String, String>,
)
