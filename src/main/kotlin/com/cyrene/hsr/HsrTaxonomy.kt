package com.cyrene.hsr

import com.cyrene.hsr.HsrCharacterService.Companion.containsWord
import com.cyrene.hsr.HsrCharacterService.Companion.normalize

/**
 * The two closed vocabularies of Honkai: Star Rail — the nine Paths (Caminhos) and the seven
 * Elements — mapped from every spelling that reaches us to one canonical PT-BR label.
 *
 * Two independent problems need this, which is why it's a shared object rather than a private
 * helper on either caller:
 *
 *  1. **Storage is not uniform.** `personagem_hsr` carries PT labels for released units but the
 *     ENGLISH ones for betas srs hasn't localized ("Elation"/"Remembrance" as caminho,
 *     "Wind"/"Quantum" as elemento). Canonicalizing on read means a path/element comparison is a
 *     string equality again, instead of every caller re-learning that Quantum == Quântico.
 *  2. **Players type neither form exactly.** "março de caça", "personagens de gelo", "quem é da
 *     harmonia" — [pathIn]/[elementIn] pull the canonical label out of free text, which is what
 *     lets [HsrCharacterService.disambiguate] separate the two 7 de Março rows and what backs the
 *     roster filters.
 *
 * Aliases are matched whole-word over [normalize]d text, so they're written accent-free and
 * lowercase here; the canonical KEY keeps its accents because it's what gets displayed.
 */
object HsrTaxonomy {

    /** Canonical PT path → the normalized tokens that name it (PT + EN). */
    val PATHS: Map<String, List<String>> = mapOf(
        "A Caça" to listOf("caca", "hunt"),
        "A Preservação" to listOf("preservacao", "preservation"),
        "A Destruição" to listOf("destruicao", "destruction"),
        "A Erudição" to listOf("erudicao", "erudition"),
        "A Harmonia" to listOf("harmonia", "harmony"),
        "A Abundância" to listOf("abundancia", "abundance"),
        "A Inexistência" to listOf("inexistencia", "nihility"),
        "A Recordação" to listOf("recordacao", "remembrance"),
        "A Euforia" to listOf("euforia", "elation"),
    )

    /** Canonical PT element → the normalized tokens that name it (PT + EN). */
    val ELEMENTS: Map<String, List<String>> = mapOf(
        "Fogo" to listOf("fogo", "fire"),
        "Gelo" to listOf("gelo", "ice"),
        "Físico" to listOf("fisico", "physical"),
        "Imaginário" to listOf("imaginario", "imaginary"),
        "Quântico" to listOf("quantico", "quantum"),
        "Raio" to listOf("raio", "lightning", "thunder"),
        "Vento" to listOf("vento", "wind"),
    )

    /** Canonical label for a value AS STORED in a column ("Elation" → "A Euforia"); null if unknown. */
    fun canonicalPath(raw: String?): String? = canonical(raw, PATHS)

    /** Canonical label for a value AS STORED in a column ("Wind" → "Vento"); null if unknown. */
    fun canonicalElement(raw: String?): String? = canonical(raw, ELEMENTS)

    /** The path named somewhere in free text ("março de caça" → "A Caça"); null when none is. */
    fun pathIn(text: String): String? = firstIn(text, PATHS)

    /** The element named somewhere in free text ("5 personagens de gelo" → "Gelo"); null when none is. */
    fun elementIn(text: String): String? = firstIn(text, ELEMENTS)

    /**
     * A stored value canonicalizes when it IS the canonical label (accent-insensitively) or when
     * it carries one of the aliases as a whole word — the second arm is what maps the English
     * beta spellings, and it tolerates the article the PT labels are stored with.
     */
    private fun canonical(raw: String?, vocab: Map<String, List<String>>): String? {
        val n = normalize(raw ?: return null)
        if (n.isEmpty()) return null
        return vocab.entries.firstOrNull { (canon, aliases) ->
            n == normalize(canon) || aliases.any { containsWord(n, it) }
        }?.key
    }

    /**
     * Exact whole-word alias match first; on a total miss, one typo-tolerant pass over the query's
     * own words. The vocabularies are closed and tiny (9 Paths, 7 Elements), so a near-miss here
     * can only ever land on a real Path/Element — unlike a name, there is no wrong-but-plausible
     * neighbour to resolve to. Without it "recordaçãp" named no Path and the caller silently fell
     * back to every Trailblazer.
     */
    private fun firstIn(text: String, vocab: Map<String, List<String>>): String? {
        val n = normalize(text)
        if (n.isEmpty()) return null
        vocab.entries.firstOrNull { (_, aliases) -> aliases.any { containsWord(n, it) } }?.let { return it.key }
        val words = n.split(' ').filter { it.length >= 4 }
        if (words.isEmpty()) return null
        return vocab.entries.firstOrNull { (_, aliases) ->
            aliases.any { a -> words.any { HsrCharacterService.nearWord(it, a) } }
        }?.key
    }
}
