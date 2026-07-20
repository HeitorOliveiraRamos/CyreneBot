package com.cyrene.hsr

import com.cyrene.config.BotProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Populates the V17 SRS+nanoka tables from [SrsNanokaHarvester] — the single upstream fetch behind
 * both the deterministic answer services and the vector store.
 *
 * Two triggers, same [populate] body:
 *  - automatic, from [com.cyrene.knowledge.KbFreshnessCheck] when nanoka starts serving a newer
 *    data version (it then reindexes the store from these tables) — the hands-off path;
 *  - manual, as a [CommandLineRunner] gated by `bot.knowledge.populate-srs-nanoka`
 *    (env `POPULATE_SRS_NANOKA=true`), which returns immediately when the flag is off so normal
 *    boots are untouched. Flyway applies V17 on the same boot, so the tables exist before this writes.
 *
 * Every row is an idempotent upsert on its natural key (`character_id` / `nome`), so re-runs are
 * safe. Character and cone PKs are captured from `RETURNING` and joined in memory to set each
 * signature cone's `id_personagem_hsr_atribuido`. A sanity floor (`< MIN_CHARS`) aborts a clearly
 * broken harvest rather than writing garbage — mirroring [HsrCharacterService]'s store guard.
 */
@Component
class SrsNanokaPopulator(
    private val properties: BotProperties,
    private val jdbc: JdbcTemplate,
    private val harvester: SrsNanokaHarvester,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(vararg args: String?) {
        if (!properties.knowledge.populateSrsNanoka) return
        populate()
        log.info("Restart WITHOUT POPULATE_SRS_NANOKA to run normally.")
    }

    fun populate() {
        val data = harvester.harvest()
        if (data.personagens.size < MIN_CHARS) {
            log.warn("srs_nanoka: colheita implausível ({} personagens) — abortando sem gravar", data.personagens.size)
            return
        }

        val charPk = HashMap<String, Int>()
        data.personagens.forEach { p -> upsertPersonagem(p)?.let { charPk[p.characterId] = it } }
        // gameId -> PK, so a recommended build's set/cone ids resolve to FKs (same as the sig link).
        val relicPk = HashMap<String, Int>()
        data.reliquias.forEach { r -> upsertReliquia(r)?.let { pk -> r.gameId?.let { relicPk[it] = pk } } }
        val ornamentPk = HashMap<String, Int>()
        data.ornamentos.forEach { o -> upsertOrnamento(o)?.let { pk -> o.gameId?.let { ornamentPk[it] = pk } } }
        val conePk = HashMap<String, Int>()
        data.cones.forEach { c -> upsertCone(c)?.let { conePk[c.coneGameId] = it } }

        var linked = 0
        data.signatureLinks.forEach { (coneGameId, charGameId) ->
            val cp = conePk[coneGameId] ?: return@forEach
            val pp = charPk[charGameId] ?: return@forEach
            jdbc.update("UPDATE cones_de_luz SET id_personagem_hsr_atribuido = ? WHERE id_cone_de_luz = ?", pp, cp)
            linked++
        }

        val builds = insertBuilds(data.builds, charPk, relicPk, ornamentPk, conePk)

        log.info(
            "srs_nanoka: gravados {} personagens, {} relíquias, {} ornamentos, {} cones ({} assinaturas, {} builds)",
            charPk.size, data.reliquias.size, data.ornamentos.size, conePk.size, linked, builds,
        )
    }

    /**
     * Rebuilds the `builds` table wholesale (it's a derived leaf: no children, one row per
     * character). Cheaper and simpler than a natural-key upsert — the recommendations are
     * fully regenerated each harvest. FKs resolved via the in-memory PK maps; a build for a
     * character/item that didn't persist is skipped rather than failing the FK.
     */
    // ponytail: DELETE + insert; add UNIQUE(id_personagem_hsr) + ON CONFLICT if partial writes ever matter.
    private fun insertBuilds(
        builds: List<Build>,
        charPk: Map<String, Int>,
        relicPk: Map<String, Int>,
        ornamentPk: Map<String, Int>,
        conePk: Map<String, Int>,
    ): Int {
        jdbc.update("DELETE FROM builds")
        var written = 0
        builds.forEach { b ->
            val pp = charPk[b.characterGameId] ?: return@forEach
            fun fk(ids: List<String>, i: Int, map: Map<String, Int>): Int? = ids.getOrNull(i)?.let { map[it] }
            jdbc.update(
                BUILD_SQL,
                pp,
                fk(b.reliquiaGameIds, 0, relicPk), fk(b.reliquiaGameIds, 1, relicPk), fk(b.reliquiaGameIds, 2, relicPk),
                fk(b.ornamentoGameIds, 0, ornamentPk), fk(b.ornamentoGameIds, 1, ornamentPk), fk(b.ornamentoGameIds, 2, ornamentPk),
                fk(b.coneGameIds, 0, conePk), fk(b.coneGameIds, 1, conePk), fk(b.coneGameIds, 2, conePk),
                b.mainStatCorpo, b.mainStatPes, b.mainStatEsfera, b.mainStatCorda,
                b.substatusRecomendados, b.equipeRecomendada,
            )
            written++
        }
        return written
    }

    // -------------------- upserts -------------------- //

    /** Upserts one character on `character_id`; returns its `id_personagem_hsr` (null if id non-numeric). */
    private fun upsertPersonagem(p: PersonagemHsr): Int? {
        val charId = p.characterId.toIntOrNull() ?: run {
            log.warn("srs_nanoka: character_id não numérico '{}' — pulando", p.characterId)
            return null
        }
        fun pair(n: NamedText) = listOf(n.nome, n.descricao)
        val params = buildList<Any?> {
            add(charId)
            addAll(listOf(p.nome, p.nomeEn, p.elemento, p.caminho, p.raridade, p.faccao, p.descricao))
            listOf(p.atqBasico, p.pericia, p.periciaSuprema, p.talento, p.tecnica,
                p.periciaMemoespirito, p.talentoMemoespirito, p.periciaEuforia).forEach { addAll(pair(it)) }
            (0..2).forEach { addAll(pair(p.tracos.getOrElse(it) { NamedText.EMPTY })) }
            (0..5).forEach { addAll(pair(p.eidolons.getOrElse(it) { NamedText.EMPTY })) }
            add(p.detalhesPersonagem)
            (0..3).forEach { add(p.historias.getOrNull(it)) }
        }
        return jdbc.queryForObject(PERSONAGEM_SQL, Int::class.java, *params.toTypedArray())
    }

    /** Upserts one cavern set on `nome`; returns its `id_reliquia` for the builds FK map. */
    private fun upsertReliquia(r: Reliquia): Int? = jdbc.queryForObject(
        RELIQUIA_SQL, Int::class.java,
        r.nome, r.efeito2Pecas, r.efeito4Pecas,
        r.cabeca.nome, r.cabeca.descricao, r.maos.nome, r.maos.descricao,
        r.corpo.nome, r.corpo.descricao, r.pes.nome, r.pes.descricao,
    )

    /** Upserts one planar set on `nome`; returns its `id_ornamento_plano` for the builds FK map. */
    private fun upsertOrnamento(o: OrnamentoPlano): Int? = jdbc.queryForObject(
        ORNAMENTO_SQL, Int::class.java,
        o.nome, o.efeito2Pecas, o.esfera.nome, o.esfera.descricao, o.corda.nome, o.corda.descricao,
    )

    /** Upserts one cone on `nome`; returns its `id_cone_de_luz`. Never touches the signature column. */
    private fun upsertCone(c: ConeDeLuz): Int? = jdbc.queryForObject(
        CONE_SQL, Int::class.java,
        c.nome, c.caminho, c.raridade, c.efeitoNome, c.efeitoDescricao, c.descricao,
    )

    private companion object {
        const val MIN_CHARS = 50

        /** `id_personagem_hsr` serial excluded; `character_id` is the conflict key (not in the SET). */
        private val PERSONAGEM_COLS = listOf(
            "character_id", "nome", "nome_en", "elemento", "caminho", "raridade", "faccao", "descricao",
            "atq_basico_nome", "atq_basico_descricao", "pericia_nome", "pericia_descricao",
            "pericia_suprema_nome", "pericia_suprema_descricao", "talento_nome", "talento_descricao",
            "tecnica_nome", "tecnica_descricao",
            "pericia_memoespirito_nome", "pericia_memoespirito_descricao",
            "talento_memoespirito_nome", "talento_memoespirito_descricao",
            "pericia_euforia_nome", "pericia_euforia_descricao",
            "traco_a2_nome", "traco_a2_descricao", "traco_a4_nome", "traco_a4_descricao",
            "traco_a6_nome", "traco_a6_descricao",
            "eidolon1_nome", "eidolon1_descricao", "eidolon2_nome", "eidolon2_descricao",
            "eidolon3_nome", "eidolon3_descricao", "eidolon4_nome", "eidolon4_descricao",
            "eidolon5_nome", "eidolon5_descricao", "eidolon6_nome", "eidolon6_descricao",
            "detalhes_personagem", "historia_personagem_parte1", "historia_personagem_parte2",
            "historia_personagem_parte3", "historia_personagem_parte4",
        )

        // Generated so the column list, placeholders and SET clause can never drift apart.
        private val PERSONAGEM_SQL = buildUpsert(
            "personagem_hsr", PERSONAGEM_COLS, conflict = "character_id", returning = "id_personagem_hsr",
        )

        private val RELIQUIA_SQL = buildUpsert(
            "reliquias",
            listOf("nome", "efeito_2_pecas", "efeito_4_pecas",
                "cabeca_nome", "cabeca_descricao", "maos_nome", "maos_descricao",
                "corpo_nome", "corpo_descricao", "pes_nome", "pes_descricao"),
            conflict = "nome", returning = "id_reliquia",
        )

        private val ORNAMENTO_SQL = buildUpsert(
            "ornamentos_planos",
            listOf("nome", "efeito_2_pecas", "esfera_nome", "esfera_descricao", "corda_nome", "corda_descricao"),
            conflict = "nome", returning = "id_ornamento_plano",
        )

        /** Plain insert into the derived `builds` leaf (wiped first each run — see [insertBuilds]). */
        private val BUILD_SQL =
            "INSERT INTO builds (id_personagem_hsr, " +
                "id_reliquia1, id_reliquia2, id_reliquia3, " +
                "id_ornamento_plano1, id_ornamento_plano2, id_ornamento_plano3, " +
                "id_cone_de_luz1, id_cone_de_luz2, id_cone_de_luz3, " +
                "main_stat_corpo, main_stat_pes, main_stat_esfera, main_stat_corda, " +
                "substatus_recomendados, equipe_recomendada) VALUES (${(1..16).joinToString(", ") { "?" }})"

        private val CONE_SQL = buildUpsert(
            "cones_de_luz",
            listOf("nome", "caminho", "raridade", "efeito_nome", "efeito_descricao", "descricao"),
            conflict = "nome", returning = "id_cone_de_luz",
        )

        /**
         * `INSERT … ON CONFLICT (conflict) DO UPDATE SET (every non-conflict col = EXCLUDED)` with an
         * optional `RETURNING`. `personagem_hsr` also stamps `data_exportado = now()`.
         */
        private fun buildUpsert(table: String, cols: List<String>, conflict: String, returning: String? = null): String {
            val stamp = table == "personagem_hsr"
            val insertCols = cols + if (stamp) listOf("data_exportado") else emptyList()
            val values = cols.joinToString(", ") { "?" } + if (stamp) ", now()" else ""
            val set = (cols.filter { it != conflict } + if (stamp) listOf("data_exportado") else emptyList())
                .joinToString(", ") { "$it = EXCLUDED.$it" }
            return buildString {
                append("INSERT INTO $table (${insertCols.joinToString(", ")}) VALUES ($values) ")
                append("ON CONFLICT ($conflict) DO UPDATE SET $set")
                returning?.let { append(" RETURNING $it") }
            }
        }
    }
}
