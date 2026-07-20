package com.cyrene.hsr

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet

/**
 * Read access to the rich V17 tables (`personagem_hsr` / `reliquias` / `ornamentos_planos` /
 * `cones_de_luz` / `builds`) — the single seam both the deterministic answer services
 * ([com.cyrene.knowledge.KitAnswerService], [com.cyrene.knowledge.ItemAnswerService],
 * [com.cyrene.knowledge.BuildAnswerService]) and the vector-store rebuild
 * ([com.cyrene.knowledge.TableKnowledgeSource]) read through, so the column→model mapping
 * lives in exactly one place.
 *
 * All reads fail-open: a DB hiccup returns empty/null so a caller degrades (falls through to
 * retrieval) rather than throwing. Rows come back as the same [PersonagemHsr]/[Reliquia]/
 * [OrnamentoPlano]/[ConeDeLuz] harvest models (transient game-id fields are absent on reads),
 * plus the render-oriented [ItemEffect]/[BuildView] views the build/item paths need.
 */
@Component
class HsrRepository(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Characters by shared game id ([ids] null = every row). Ids are bound as [Int]: `character_id`
     * is an INTEGER column and PgJDBC types a String param as varchar, which Postgres rejects
     * outright ("operator does not exist: integer = character varying"). Non-numeric ids can't
     * match anything, so an all-non-numeric [ids] returns empty rather than degrading to "no filter".
     */
    fun personagens(ids: Collection<String>? = null): List<PersonagemHsr> {
        val numeric = ids?.mapNotNull(String::toIntOrNull)
        if (numeric != null && numeric.isEmpty()) return emptyList()
        return query(
            "SELECT * FROM personagem_hsr" + inClause("character_id", numeric),
            PERSONAGEM_MAPPER, numeric,
        )
    }

    fun allReliquias(): List<Reliquia> = query("SELECT * FROM reliquias", RELIQUIA_MAPPER)
    fun allOrnamentos(): List<OrnamentoPlano> = query("SELECT * FROM ornamentos_planos", ORNAMENTO_MAPPER)
    fun allCones(): List<ConeDeLuz> = query("SELECT * FROM cones_de_luz", CONE_MAPPER)

    /**
     * The light cone DESIGNED for a character — `cones_de_luz.id_personagem_hsr_atribuido`, set
     * from each character's #1 recommended cone at populate time. Null for the ~2/3 of cones that
     * are generic (only 64 of 169 carry an owner), which is why every caller treats a miss as
     * "fall back to the recommended list" rather than as an error.
     */
    fun coneAssinatura(characterId: String): ConeDeLuz? {
        val id = characterId.toIntOrNull() ?: return null
        return query(
            "SELECT c.* FROM cones_de_luz c " +
                "JOIN personagem_hsr p ON c.id_personagem_hsr_atribuido = p.id_personagem_hsr " +
                "WHERE p.character_id = ?",
            CONE_MAPPER, listOf(id),
        ).firstOrNull()
    }

    /** Every relic/ornament/cone name, for the item path's name matching. */
    fun itemNames(): List<String> = query(
        "SELECT nome FROM reliquias UNION SELECT nome FROM ornamentos_planos UNION SELECT nome FROM cones_de_luz",
        { rs, _ -> rs.getString("nome") },
    ).filterNotNull()

    /**
     * FULL rows for the items named in [names] — unlike [itemsByName], which reduces each item to
     * its effect lines, these carry every column so the field-projection path can answer about one
     * piece ("o nome dos pés do set X") or a cone's lore.
     */
    fun reliquiasByName(names: Collection<String>): List<Reliquia> =
        if (names.isEmpty()) emptyList()
        else query("SELECT * FROM reliquias" + inClause("nome", names), RELIQUIA_MAPPER, names)

    fun ornamentosByName(names: Collection<String>): List<OrnamentoPlano> =
        if (names.isEmpty()) emptyList()
        else query("SELECT * FROM ornamentos_planos" + inClause("nome", names), ORNAMENTO_MAPPER, names)

    fun conesByName(names: Collection<String>): List<ConeDeLuz> =
        if (names.isEmpty()) emptyList()
        else query("SELECT * FROM cones_de_luz" + inClause("nome", names), CONE_MAPPER, names)

    /** Effect views for the items named in [names] (exact match), across all three item tables. */
    fun itemsByName(names: Collection<String>): List<ItemEffect> {
        if (names.isEmpty()) return emptyList()
        return query(RELIQUIA_EFFECT_SQL + inClause("nome", names), reliquiaEffect(), names) +
            query(ORNAMENTO_EFFECT_SQL + inClause("nome", names), ornamentoEffect(), names) +
            query(CONE_EFFECT_SQL + inClause("nome", names), coneEffect(), names)
    }

    /**
     * Recommended builds, item FKs resolved to [ItemEffect]s in slot order. [characterIds]
     * null/empty = every build (the reindex needs all; the build answer scopes to one char).
     * Item tables are loaded once into PK maps, so there's no per-build N+1.
     */
    fun builds(characterIds: Collection<String>? = null): List<BuildView> {
        // Bound as Int for the same INTEGER-vs-varchar reason as [personagens].
        val numeric = characterIds?.mapNotNull(String::toIntOrNull)
        if (numeric != null && numeric.isEmpty()) return emptyList()
        val relics = effectsByPk(RELIQUIA_PK_SQL, reliquiaEffect())
        val orns = effectsByPk(ORNAMENTO_PK_SQL, ornamentoEffect())
        val cones = effectsByPk(CONE_PK_SQL, coneEffect())
        val sql = "SELECT b.*, p.character_id, p.nome, p.nome_en FROM builds b " +
            "JOIN personagem_hsr p ON b.id_personagem_hsr = p.id_personagem_hsr" +
            inClause("p.character_id", numeric)
        return query(sql, { rs, _ ->
            fun items(vararg cols: String, from: Map<Int, ItemEffect>) =
                cols.mapNotNull { c -> rs.getInt(c).takeUnless { rs.wasNull() }?.let { from[it] } }
            BuildView(
                characterId = rs.getInt("character_id").toString(),
                nome = rs.getString("nome"),
                nomeEn = rs.getString("nome_en"),
                reliquias = items("id_reliquia1", "id_reliquia2", "id_reliquia3", from = relics),
                ornamentos = items("id_ornamento_plano1", "id_ornamento_plano2", "id_ornamento_plano3", from = orns),
                cones = items("id_cone_de_luz1", "id_cone_de_luz2", "id_cone_de_luz3", from = cones),
                mainStatCorpo = rs.getString("main_stat_corpo"),
                mainStatPes = rs.getString("main_stat_pes"),
                mainStatEsfera = rs.getString("main_stat_esfera"),
                mainStatCorda = rs.getString("main_stat_corda"),
                substatusRecomendados = rs.getString("substatus_recomendados"),
                equipeRecomendada = rs.getString("equipe_recomendada"),
            )
        }, numeric)
    }

    // -------------------- internals -------------------- //

    // Fail-open, but never silent: callers degrade to the retrieval path on empty, and a silent
    // catch here once hid a live bind-type bug (String param vs the INTEGER character_id column)
    // that made every deterministic answer fall through.
    private fun <T> query(sql: String, mapper: RowMapper<T>, args: Collection<*>? = null): List<T> = try {
        if (args == null) jdbc.query(sql, mapper) else jdbc.query(sql, mapper, *args.toTypedArray())
    } catch (e: Exception) {
        log.warn("HsrRepository query failed [{}]: {}", sql.substringBefore(" FROM"), e.message)
        emptyList()
    }

    private fun effectsByPk(sql: String, mapper: RowMapper<ItemEffect>): Map<Int, ItemEffect> = try {
        jdbc.query(sql) { rs, i -> rs.getInt("id") to mapper.mapRow(rs, i)!! }.toMap()
    } catch (e: Exception) {
        log.warn("HsrRepository item load failed [{}]: {}", sql.substringBefore(" FROM"), e.message)
        emptyMap()
    }

    private fun reliquiaEffect(): RowMapper<ItemEffect> = RowMapper { rs, _ -> reliquiaEffect(rs) }
    private fun ornamentoEffect(): RowMapper<ItemEffect> = RowMapper { rs, _ -> ornamentoEffect(rs) }
    private fun coneEffect(): RowMapper<ItemEffect> = RowMapper { rs, _ -> coneEffect(rs) }

    private companion object {
        private fun inClause(col: String, args: Collection<*>?): String =
            if (args.isNullOrEmpty()) "" else " WHERE $col IN (${args.joinToString(",") { "?" }})"

        private fun ResultSet.smallint(col: String): Int? = getInt(col).takeUnless { wasNull() }
        private fun ResultSet.nt(col: String) = NamedText(getString("${col}_nome"), getString("${col}_descricao"))

        private val PERSONAGEM_MAPPER = RowMapper { rs, _ ->
            PersonagemHsr(
                characterId = rs.getInt("character_id").toString(),
                nome = rs.getString("nome"),
                nomeEn = rs.getString("nome_en"),
                elemento = rs.getString("elemento"),
                caminho = rs.getString("caminho"),
                raridade = rs.smallint("raridade"),
                faccao = rs.getString("faccao"),
                descricao = rs.getString("descricao"),
                atqBasico = rs.nt("atq_basico"),
                pericia = rs.nt("pericia"),
                periciaSuprema = rs.nt("pericia_suprema"),
                talento = rs.nt("talento"),
                tecnica = rs.nt("tecnica"),
                periciaMemoespirito = rs.nt("pericia_memoespirito"),
                talentoMemoespirito = rs.nt("talento_memoespirito"),
                periciaEuforia = rs.nt("pericia_euforia"),
                tracos = listOf(rs.nt("traco_a2"), rs.nt("traco_a4"), rs.nt("traco_a6")).filterNot { it.isBlank },
                eidolons = (1..6).map { rs.nt("eidolon$it") }.filterNot { it.isBlank },
                detalhesPersonagem = rs.getString("detalhes_personagem"),
                historias = (1..4).map { rs.getString("historia_personagem_parte$it") },
            )
        }

        private val RELIQUIA_MAPPER = RowMapper { rs, _ ->
            Reliquia(
                nome = rs.getString("nome"),
                efeito2Pecas = rs.getString("efeito_2_pecas"),
                efeito4Pecas = rs.getString("efeito_4_pecas"),
                cabeca = rs.nt("cabeca"), maos = rs.nt("maos"), corpo = rs.nt("corpo"), pes = rs.nt("pes"),
            )
        }

        private val ORNAMENTO_MAPPER = RowMapper { rs, _ ->
            OrnamentoPlano(
                nome = rs.getString("nome"),
                efeito2Pecas = rs.getString("efeito_2_pecas"),
                esfera = rs.nt("esfera"), corda = rs.nt("corda"),
            )
        }

        private val CONE_MAPPER = RowMapper { rs, _ ->
            ConeDeLuz(
                nome = rs.getString("nome"),
                caminho = rs.getString("caminho"),
                raridade = rs.smallint("raridade"),
                efeitoNome = rs.getString("efeito_nome"),
                efeitoDescricao = rs.getString("efeito_descricao"),
                descricao = rs.getString("descricao"),
            )
        }

        // Effect views (name + kind + the bonus/effect lines) — same line shape both answer paths render.
        private const val RELIC_KIND = "Conjunto de Relíquia (Cavern Relics)"
        private const val ORNAMENT_KIND = "Ornamento Planar (Planar Ornament)"
        private const val CONE_KIND = "Cone de Luz (Light Cone)"

        private fun reliquiaEffect(rs: ResultSet) = ItemEffect(
            rs.getString("nome"), RELIC_KIND,
            listOfNotNull(
                rs.getString("efeito_2_pecas")?.let { "Bônus 2 peças: $it" },
                rs.getString("efeito_4_pecas")?.let { "Bônus 4 peças: $it" },
            ),
        )

        private fun ornamentoEffect(rs: ResultSet) = ItemEffect(
            rs.getString("nome"), ORNAMENT_KIND,
            listOfNotNull(rs.getString("efeito_2_pecas")?.let { "Bônus 2 peças: $it" }),
        )

        private fun coneEffect(rs: ResultSet): ItemEffect {
            val name = rs.getString("efeito_nome")
            val desc = rs.getString("efeito_descricao")
            val line = when {
                desc == null -> null
                name != null -> "Efeito ($name): $desc"
                else -> "Efeito: $desc"
            }
            return ItemEffect(rs.getString("nome"), CONE_KIND, listOfNotNull(line))
        }

        private const val RELIQUIA_EFFECT_SQL = "SELECT nome, efeito_2_pecas, efeito_4_pecas FROM reliquias"
        private const val ORNAMENTO_EFFECT_SQL = "SELECT nome, efeito_2_pecas FROM ornamentos_planos"
        private const val CONE_EFFECT_SQL = "SELECT nome, efeito_nome, efeito_descricao FROM cones_de_luz"
        private const val RELIQUIA_PK_SQL = "SELECT id_reliquia AS id, nome, efeito_2_pecas, efeito_4_pecas FROM reliquias"
        private const val ORNAMENTO_PK_SQL = "SELECT id_ornamento_plano AS id, nome, efeito_2_pecas FROM ornamentos_planos"
        private const val CONE_PK_SQL = "SELECT id_cone_de_luz AS id, nome, efeito_nome, efeito_descricao FROM cones_de_luz"
    }
}

/** A relic/ornament/cone reduced to what the build & item answers render: name, kind, effect lines. */
data class ItemEffect(val nome: String, val kind: String, val efeitos: List<String>)

/**
 * A recommended build read back from the DB with its item FKs resolved to [ItemEffect]s (slot
 * order preserved, empty slots dropped) and the main-stat/substat/team free-text fields carried
 * verbatim. The character is identified by [characterId] and its display [nome]/[nomeEn].
 */
data class BuildView(
    val characterId: String,
    val nome: String?,
    val nomeEn: String?,
    val reliquias: List<ItemEffect> = emptyList(),
    val ornamentos: List<ItemEffect> = emptyList(),
    val cones: List<ItemEffect> = emptyList(),
    val mainStatCorpo: String? = null,
    val mainStatPes: String? = null,
    val mainStatEsfera: String? = null,
    val mainStatCorda: String? = null,
    val substatusRecomendados: String? = null,
    val equipeRecomendada: String? = null,
) {
    val displayName: String get() = nome ?: nomeEn ?: characterId
}
