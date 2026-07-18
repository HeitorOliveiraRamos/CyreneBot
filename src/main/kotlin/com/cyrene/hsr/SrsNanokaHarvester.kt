package com.cyrene.hsr

import com.cyrene.config.BotProperties
import com.cyrene.knowledge.NanokaIngestionSource
import com.cyrene.knowledge.NanokaIngestionSource.Companion.children
import com.cyrene.knowledge.NanokaIngestionSource.Companion.fill
import com.cyrene.knowledge.NanokaIngestionSource.Companion.strip
import com.cyrene.knowledge.StarRailStationIngestionSource
import com.cyrene.knowledge.StarRailStationIngestionSource.Companion.hashPath
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.cyrene.knowledge.NanokaIngestionSource.Companion.maxLevelParams as nanMaxLevel
import com.cyrene.knowledge.NanokaIngestionSource.Companion.paramList as nanParams
import com.cyrene.knowledge.StarRailStationIngestionSource.Companion.maxLevelParams as srsMaxLevel
import com.cyrene.knowledge.StarRailStationIngestionSource.Companion.paramList as srsParams
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Harvests the rich V17 schema ([PersonagemHsr] / [Reliquia] / [OrnamentoPlano] / [ConeDeLuz])
 * from the two structured-JSON sources, PT-first — the eventual replacement for
 * [HsrCharacterHarvester]. Same spine/overlay join as that class:
 *
 *  - **nanoka** ([NanokaIngestionSource]) is the SPINE for characters and light cones — its
 *    numeric id index lists every id including betas srs hasn't published. Text is English.
 *  - **starrailstation** ([StarRailStationIngestionSource]) is the PT overlay (join key
 *    `rankKey` == the numeric id), plus the SOLE source of relic/ornament pieces (nanoka carries
 *    no per-piece lore).
 *
 * Beyond [HsrCharacterHarvester] this splits every ability/eidolon/trace into a name/description
 * pair, and adds memosprite (Recordação), euphoria (Euforia), relic/ornament pieces and light-cone
 * lore. The two ingestion beans are injected only to reuse their version/deployment discovery and
 * hardened pure parsers (skill grouping, trace walk, [fill]/[strip], the srs path [hashPath]);
 * every extractor is a pure companion function, fixture-tested.
 *
 * The signature-cone link (a cone → the 5★ character it's designed for) is each character's #1
 * nanoka recommended cone, kept both-sided-5★ so generic cones don't get mislinked to 4★ units.
 */
@Component
class SrsNanokaHarvester(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
    private val nanoka: NanokaIngestionSource,
    private val srs: StarRailStationIngestionSource,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun harvest(): SrsNanokaData {
        val k = properties.knowledge
        val nanoVer = nanoka.resolveVersion() ?: run {
            log.error("srs_nanoka: nanoka version unresolved — aborting harvest.")
            return EMPTY
        }
        val nanoBase = "${k.nanokaCdnUrl.trimEnd('/')}/$nanoVer"
        val charIndex = getJson("$nanoBase/character.json") ?: run {
            log.error("srs_nanoka: nanoka character index unreachable — aborting harvest.")
            return EMPTY
        }

        val dep = srs.resolveDeployment()
        val srsByRankKey: Map<String, JsonNode> = dep?.let { d ->
            getSrs(d, "characters.json")?.path("entries")?.associateBy { it.path("rankKey").asText("") }
        }.orEmpty()
        if (dep == null || srsByRankKey.isEmpty()) {
            log.warn("srs_nanoka: starrailstation unavailable — English-only harvest (no PT, no relic/ornament pieces).")
        }

        // -------- characters + raw signature links (5★ char → its top nanoka cone) --------
        val personagens = mutableListOf<PersonagemHsr>()
        val rawSig = mutableMapOf<String, String>() // coneGameId -> characterGameId
        var withPt = 0
        for ((id, meta) in charIndex.fields()) {
            val srsEntry = srsByRankKey[id]
            val pageId = srsEntry?.path("pageId")?.asText("")?.ifBlank { null }
                ?: meta.path("icon").asText("").ifBlank { null }
            val srsDetail = if (dep != null && srsEntry != null && pageId != null) getSrs(dep, "characters/$pageId.json") else null
            if (srsEntry != null) withPt++
            val nanDetail = getJson("$nanoBase/en/character/$id.json")
            val p = buildPersonagem(id, meta, srsEntry, srsDetail, nanDetail)
            personagens += p
            if (p.raridade == 5) {
                children(nanDetail?.path("lightcones") ?: mapper.nullNode()).firstOrNull()
                    ?.asText("")?.ifBlank { null }?.let { rawSig[it] = id }
            }
        }

        // -------- relic & ornament sets (srs only — PT pieces) --------
        val reliquias = mutableListOf<Reliquia>()
        val ornamentos = mutableListOf<OrnamentoPlano>()
        if (dep != null) {
            for (entry in children(getSrs(dep, "relics.json")?.path("entries") ?: mapper.nullNode())) {
                val pageId = entry.path("pageId").asText("").ifBlank { null } ?: continue
                if (strip(entry.path("name").asText("")).isBlank()) continue
                val detail = getSrs(dep, "relics/$pageId.json")
                when (entry.path("relicType").asInt(0)) {
                    1 -> reliquias += buildReliquia(entry, detail)
                    2 -> ornamentos += buildOrnamento(entry, detail)
                }
            }
        }

        // -------- light cones (nanoka spine, srs PT overlay) --------
        val srsConeIds: Set<String> = if (dep != null) {
            children(getSrs(dep, "searchItems.json")?.path("entries") ?: mapper.nullNode())
                .filter { it.path("type").asInt(-1) == 1 }
                .mapNotNull { it.path("url").asText("").substringAfterLast('/').ifBlank { null } }
                .toSet()
        } else emptySet()
        val coneIndex = getJson("$nanoBase/lightcone.json") ?: mapper.nullNode()
        val cones = mutableListOf<ConeDeLuz>()
        for ((id, meta) in coneIndex.fields()) {
            val cone = if (dep != null && id in srsConeIds) {
                getSrs(dep, "lightcones/$id.json")?.let { buildSrsCone(id, it) }
            } else null
            val resolved = cone ?: buildNanCone(id, meta, getJson("$nanoBase/en/lightcone/$id.json"))
            if (resolved.nome.isNotBlank()) cones += resolved
        }

        // Keep a signature link only when its cone is 5★ too (avoids mislinking shared cones).
        val coneRar = cones.associate { it.coneGameId to it.raridade }
        val signatureLinks = rawSig.filter { (coneId, _) -> coneRar[coneId] == 5 }

        log.info(
            "srs_nanoka: {} personagens ({} com PT), {} relíquias, {} ornamentos, {} cones, {} assinaturas",
            personagens.size, withPt, reliquias.size, ornamentos.size, cones.size, signatureLinks.size,
        )
        return SrsNanokaData(personagens, reliquias, ornamentos, cones, signatureLinks)
    }

    // -------------------- io -------------------- //

    private fun getSrs(deployment: String, path: String): JsonNode? {
        val locized = "${properties.knowledge.srsLocale}/$path"
        val url = "${properties.knowledge.srsDataUrl.trimEnd('/')}/$deployment/${hashPath(locized)}"
        return getJson(url)
    }

    private fun getJson(url: String): JsonNode? = getText(url)?.let {
        try {
            mapper.readTree(it)
        } catch (e: Exception) {
            log.warn("srs_nanoka: bad JSON from {}: {}", url, e.message)
            null
        }
    }

    private fun getText(url: String): String? = try {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 (compatible; CyreneBot/1.0; +discord)")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() in 200..299) resp.body()
        else { log.warn("srs_nanoka: HTTP {} for {}", resp.statusCode(), url); null }
    } catch (e: Exception) {
        log.warn("srs_nanoka: fetch failed for {}: {}", url, e.message)
        null
    }

    internal companion object {
        val EMPTY = SrsNanokaData(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())

        private const val TRAILBLAZER = "Trailblazer"
        private val RARITY_DIGIT = Regex("""(\d)$""")

        /** HSR internal path codenames → readable EN (nanoka fallback for betas; srs gives PT). */
        private val PATH_EN = mapOf(
            "Warrior" to "Destruction", "Knight" to "Preservation", "Mage" to "Erudition",
            "Shaman" to "Harmony", "Warlock" to "Nihility", "Priest" to "Abundance",
            "Rogue" to "The Hunt", "Memory" to "Remembrance", "Elation" to "Elation",
        )

        /** nanoka combat types → readable EN. `Thunder` is the internal name for Lightning. */
        private val ELEMENT_EN = mapOf("Thunder" to "Lightning")

        /**
         * Realistic level cap per ability (srs PT tag / nanoka EN type_name). The source levelData
         * runs higher — Basic to 10, Skill/Ult to 15 — but those extra levels are only reachable via
         * eidolons and trace bonuses, so we fill at the level a player actually levels the ability to.
         * A type absent here (Técnica/Technique) has a single level and is filled at its max.
         */
        private val ABILITY_CAP_PT = mapOf(
            "ATQ Básico" to 6, "Perícia" to 10, "Perícia Suprema" to 10, "Talento" to 10, "Perícia da Euforia" to 10,
        )
        private val ABILITY_CAP_EN = mapOf(
            "Basic ATK" to 6, "Skill" to 10, "Ultimate" to 10, "Talent" to 10, "Elation Skill" to 10,
        )
        private const val MEMO_CAP = 6

        /** srs `levelData`: params of the highest `level` ≤ [cap] (or the max level when [cap] is null). */
        internal fun srsParamsCapped(levelData: JsonNode, cap: Int?): List<Double> {
            if (!levelData.isArray || levelData.isEmpty) return emptyList()
            if (cap == null) return srsMaxLevel(levelData)
            val entry = levelData.filter { it.path("level").asInt(0) <= cap }.maxByOrNull { it.path("level").asInt(0) }
                ?: return srsMaxLevel(levelData)
            return srsParams(entry.path("params"))
        }

        /** nanoka per-level `level` object: param_list of the highest key ≤ [cap] (or the max when null). */
        internal fun nanParamsCapped(levelNode: JsonNode, cap: Int?): List<Double> {
            if (!levelNode.isObject || levelNode.isEmpty) return emptyList()
            if (cap == null) return nanMaxLevel(levelNode)
            val key = levelNode.fieldNames().asSequence().mapNotNull { it.toIntOrNull() }.filter { it <= cap }.maxOrNull()
                ?: return nanMaxLevel(levelNode)
            return nanParams(levelNode.path(key.toString()).path("param_list"))
        }

        // ---------------- characters ---------------- //

        /**
         * Assembles one [PersonagemHsr] PT-first: each field takes the srs (Portuguese) value when
         * present, else the nanoka (English) one. Pure — fixture-testable without the network.
         *
         * Enhanced states (Firefly, Blade, Kafka, Silver Wolf…): srs flags them with `hasEnhanced`,
         * nanoka with a non-empty `enhanced` object. For those, the ability extractors pick the
         * ENHANCED variant of each skill, not the base. A NEW enhanced kit that nanoka has but srs
         * hasn't published yet ([nanokaKitWins]) is the ONE case a released character's kit is taken
         * from nanoka (English) over srs — otherwise srs (PT) stays primary.
         */
        internal fun buildPersonagem(
            id: String,
            nanMeta: JsonNode,
            srsEntry: JsonNode?,
            srsDetail: JsonNode?,
            nanDetail: JsonNode?,
        ): PersonagemHsr {
            val srsEnhanced = srsDetail?.path("hasEnhanced")?.asBoolean(false) == true
            val nanEnhanced = (nanDetail?.path("enhanced")?.size() ?: 0) > 0
            val nanokaKitWins = nanEnhanced && !srsEnhanced

            val srsAbil = srsDetail?.let { srsAbilities(it, srsEnhanced) }.orEmpty()
            val nanAbil = nanDetail?.let { nanAbilities(it, nanEnhanced) }.orEmpty()
            val srsMemo = srsDetail?.let { srsMemosprite(it) }.orEmpty()
            val nanMemo = nanDetail?.let { nanMemosprite(it) }.orEmpty()
            val stories = srsStories(srsDetail).ifEmpty { nanStories(nanDetail) }
            // PT-first normally; nanoka-first only when its new enhanced kit overrides srs.
            fun kit(srsVal: NamedText?, nanVal: NamedText?): NamedText =
                if (nanokaKitWins) pick(nanVal, srsVal) else pick(srsVal, nanVal)
            return PersonagemHsr(
                characterId = id,
                nome = srsEntry?.path("name")?.asText("")?.let(::strip)?.ifBlank { null },
                nomeEn = nanMeta.path("en").asText("").ifBlank { null }?.replace("{NICKNAME}", TRAILBLAZER)?.let(::strip),
                elemento = srsEntry?.path("damageType")?.path("name")?.asText("")?.let(::strip)?.ifBlank { null }
                    ?: nanMeta.path("damageType").asText("").ifBlank { null }?.let { ELEMENT_EN[it] ?: it },
                caminho = srsEntry?.path("baseType")?.path("name")?.asText("")?.let(::strip)?.ifBlank { null }
                    ?: nanMeta.path("baseType").asText("").ifBlank { null }?.let { PATH_EN[it] ?: it },
                raridade = srsEntry?.path("rarity")?.asInt(0)?.takeIf { it > 0 }
                    ?: RARITY_DIGIT.find(nanMeta.path("rank").asText(""))?.value?.toIntOrNull(),
                faccao = srsDetail?.path("archive")?.path("camp")?.asText("")?.ifBlank { null }
                    ?: nanDetail?.path("chara_info")?.path("camp")?.asText("")?.ifBlank { null },
                descricao = srsDetail?.path("descHash")?.asText("")?.ifBlank { null }?.let { fill(it, emptyList()) }
                    ?: nanMeta.path("desc").asText("").ifBlank { null }?.let(::strip),
                atqBasico = kit(srsAbil["ATQ Básico"], nanAbil["Basic ATK"]),
                pericia = kit(srsAbil["Perícia"], nanAbil["Skill"]),
                periciaSuprema = kit(srsAbil["Perícia Suprema"], nanAbil["Ultimate"]),
                talento = kit(srsAbil["Talento"], nanAbil["Talent"]),
                tecnica = kit(srsAbil["Técnica"], nanAbil["Technique"]),
                periciaMemoespirito = kit(srsMemo["skill"], nanMemo["Memosprite Skill"]),
                talentoMemoespirito = kit(srsMemo["talent"], nanMemo["Memosprite Talent"]),
                periciaEuforia = kit(srsAbil["Perícia da Euforia"], nanAbil["Elation Skill"]),
                tracos = srsTraces(srsDetail, srsEnhanced).ifEmpty { nanTraces(nanDetail, nanEnhanced) },
                eidolons = srsEidolons(srsDetail, srsEnhanced).ifEmpty { nanEidolons(nanDetail, nanEnhanced) },
                detalhesPersonagem = stories.getOrNull(0)?.ifBlank { null },
                historias = stories.drop(1).map { it.ifBlank { null } },
            )
        }

        /** PT-first pick: the srs pair unless it's fully blank, then nanoka, then EMPTY. */
        private fun pick(srs: NamedText?, nan: NamedText?): NamedText =
            srs?.takeUnless { it.isBlank } ?: nan?.takeUnless { it.isBlank } ?: NamedText.EMPTY

        /**
         * srs canonical skills as name/desc pairs, keyed by PT type tag ("Talento"…). Descriptions
         * are filled at the realistically achievable level ([ABILITY_CAP_PT]), not the eidolon/
         * trace-boosted max. When [enhanced], the enhanced variant of each skill is used ([srsCanonical]).
         */
        private fun srsAbilities(detail: JsonNode, enhanced: Boolean): Map<String, NamedText> =
            srsCanonical(detail, enhanced).mapNotNull { sk ->
                val tag = strip(sk.path("typeDescHash").asText(""))
                if (tag.isBlank()) return@mapNotNull null
                tag to NamedText(
                    strip(sk.path("name").asText("")).ifBlank { null },
                    fill(sk.path("descHash").asText(""), srsParamsCapped(sk.path("levelData"), ABILITY_CAP_PT[tag])).ifBlank { null },
                )
            }.toMap()

        /**
         * The 5 canonical skills keyed by id via `skillGrouping`. An enhanced-state character keeps
         * its enhanced kit in a whole alternate detail under **`.enhanced`** (its own `skills` +
         * `skillGrouping`) — Kafka/Silver Wolf keep the same ability NAMES there but with enhanced
         * descriptions, so the base `skillGrouping` alone (single-id buckets) never exposes them.
         * So for [enhanced] we source from `.enhanced` and still take the LAST id of each bucket
         * (Firefly's `.enhanced` keeps base+enhanced pairs, enhanced last); base detail, first id,
         * otherwise. Falls back to the raw skills list (deduped by name) when `skillGrouping` is absent.
         */
        internal fun srsCanonical(detail: JsonNode, enhanced: Boolean): List<JsonNode> {
            val source = if (enhanced && detail.path("enhanced").path("skills").let { it.isArray && !it.isEmpty })
                detail.path("enhanced") else detail
            val skills = source.path("skills")
            val byId = skills.associateBy { it.path("id").asLong() }
            val grouping = source.path("skillGrouping")
            if (!grouping.isArray || grouping.isEmpty) return skills.distinctBy { it.path("name").asText("") }
            return grouping.mapNotNull { group -> byId[group.path(if (enhanced) group.size() - 1 else 0).asLong()] }
        }

        /**
         * nanoka abilities keyed by EN type_name, filled at [ABILITY_CAP_EN]. nanoka keeps the
         * enhanced kit under `.enhanced.<state>.skills` (state key "1"), NOT as duplicates in the
         * base `.skills` for every character (Kafka has none there). So we take the base kit
         * (first per type) and then, when [enhanced], overlay the enhanced skills on top — the last
         * enhanced entry per type winning, so Firefly's base+enhanced pair resolves to the enhanced.
         */
        private fun nanAbilities(detail: JsonNode, enhanced: Boolean): Map<String, NamedText> {
            val out = LinkedHashMap<String, NamedText>()
            fun add(sk: JsonNode, overwrite: Boolean) {
                val type = sk.path("type_name").asText("").trim()
                if (type.isBlank() || (!overwrite && out.containsKey(type))) return
                val raw = sk.path("desc").asText("").ifBlank { sk.path("simple_desc").asText("") }
                out[type] = NamedText(strip(sk.path("name").asText("")).ifBlank { null }, fill(raw, nanParamsCapped(sk.path("level"), ABILITY_CAP_EN[type])).ifBlank { null })
            }
            children(detail.path("skills")).forEach { add(it, overwrite = false) }
            if (enhanced) {
                children(detail.path("enhanced")).lastOrNull()?.path("skills")?.let { children(it) }?.forEach { add(it, overwrite = true) }
            }
            return out
        }

        /**
         * srs memosprite Skill/Talent (Recordação only) from `.servant.skills`, keyed "skill"/
         * "talent". Servant skills use their own field names (`typeDesc`, `skillDesc`) and carry
         * the PT type label directly, so match on that rather than position. [] for non-Recordação.
         */
        private fun srsMemosprite(detail: JsonNode): Map<String, NamedText> {
            val skills = children(detail.path("servant").path("skills"))
            if (skills.isEmpty()) return emptyMap()
            fun byType(type: String): NamedText? = skills.firstOrNull { strip(it.path("typeDesc").asText("")) == type }?.let { sk ->
                NamedText(
                    strip(sk.path("name").asText("")).ifBlank { null },
                    fill(sk.path("skillDesc").asText(""), srsParamsCapped(sk.path("levelData"), MEMO_CAP)).ifBlank { null },
                )
            }
            return buildMap {
                byType("Perícia do Memoespírito")?.let { put("skill", it) }
                byType("Talento do Memoespírito")?.let { put("talent", it) }
            }
        }

        /** nanoka memosprite skills keyed by type_name ("Memosprite Skill"/"Memosprite Talent"). */
        private fun nanMemosprite(detail: JsonNode): Map<String, NamedText> = buildMap {
            children(detail.path("memosprite").path("skills")).forEach { sk ->
                val type = sk.path("type_name").asText("").trim()
                if (type.isBlank() || containsKey(type)) return@forEach
                val raw = sk.path("desc").asText("").ifBlank { sk.path("simple_desc").asText("") }
                put(type, NamedText(strip(sk.path("name").asText("")).ifBlank { null }, fill(raw, nanParamsCapped(sk.path("level"), MEMO_CAP)).ifBlank { null }))
            }
        }

        /**
         * The 3 major traces (A2/A4/A6). An enhanced character's `skillTreePoints` carries BOTH the
         * base (`enhanceId == 0`) and the enhanced (`enhanceId > 0`) variant of each trace — same
         * names, different text — so for [enhanced] we keep the enhanced variant (falling back to
         * base when a character has no enhanced trace). `.enhanced.skillTreePoints` is empty, so the
         * enhanced traces live in the BASE tree, unlike the enhanced skills/eidolons.
         */
        private fun srsTraces(detail: JsonNode?, enhanced: Boolean): List<NamedText> {
            detail ?: return emptyList()
            return srsMajorTraces(detail, enhanced).map { pt ->
                val bonus = pt.path("embedBonusSkill")
                NamedText(
                    strip(bonus.path("name").asText("")).ifBlank { null },
                    fill(bonus.path("descHash").asText(""), srsMaxLevel(bonus.path("levelData"))).ifBlank { null },
                )
            }.filterNot { it.isBlank }
        }

        /** type==1 trace nodes (recursive walk); the enhanceId>0 variant for [enhanced], else base. */
        private fun srsMajorTraces(detail: JsonNode, enhanced: Boolean): List<JsonNode> {
            val nodes = mutableListOf<JsonNode>()
            fun walk(n: JsonNode) {
                // add(), not +=: JsonNode is Iterable<JsonNode>, so += concat-copies its children.
                if (n.path("type").asInt() == 1) nodes.add(n)
                n.path("children").forEach { walk(it) }
            }
            detail.path("skillTreePoints").forEach { walk(it) }
            val base = nodes.filter { it.path("enhanceId").asInt(0) == 0 }
            if (!enhanced) return base
            return nodes.filter { it.path("enhanceId").asInt(0) > 0 }.ifEmpty { base }
        }

        private fun nanTraces(detail: JsonNode?, enhanced: Boolean): List<NamedText> {
            detail ?: return emptyList()
            val enhTrees = if (enhanced) children(detail.path("enhanced")).lastOrNull()?.path("skill_trees") else null
            val trees = enhTrees?.takeIf { !it.isEmpty } ?: detail.path("skill_trees")
            return children(trees).mapNotNull { pt ->
                val node = pt.path("1")
                val raw = node.path("point_desc").asText("").trim()
                if (raw.isBlank()) return@mapNotNull null
                NamedText(strip(node.path("point_name").asText("")).ifBlank { null }, fill(raw, nanParams(node.path("param_list"))).ifBlank { null })
            }
        }

        /** Eidolons; the enhanced set (`.enhanced.ranks`, Kafka's differ from base) for [enhanced]. */
        private fun srsEidolons(detail: JsonNode?, enhanced: Boolean): List<NamedText> {
            detail ?: return emptyList()
            val enh = detail.path("enhanced").path("ranks")
            val ranks = if (enhanced && enh.isArray && !enh.isEmpty) enh else detail.path("ranks")
            return children(ranks).map { rk ->
                NamedText(strip(rk.path("name").asText("")).ifBlank { null }, fill(rk.path("descHash").asText(""), srsParams(rk.path("params"))).ifBlank { null })
            }
        }

        private fun nanEidolons(detail: JsonNode?, enhanced: Boolean): List<NamedText> {
            detail ?: return emptyList()
            val enhRanks = if (enhanced) children(detail.path("enhanced")).lastOrNull()?.path("ranks") else null
            val ranks = enhRanks?.takeIf { !it.isEmpty } ?: detail.path("ranks")
            return children(ranks).map { rk ->
                NamedText(strip(rk.path("name").asText("")).ifBlank { null }, fill(rk.path("desc").asText(""), nanParams(rk.path("param_list"))).ifBlank { null })
            }
        }

        /** srs stories as [detalhes, parte1..4] (detalhes "" if absent so the parts keep their slots). */
        private fun srsStories(detail: JsonNode?): List<String> {
            val items = detail?.let { children(it.path("storyItems")) }.orEmpty()
            if (items.isEmpty()) return emptyList()
            val details = items.firstOrNull { it.path("title").asText("").startsWith("Detalhes") }
            val parts = items.filter { it.path("title").asText("").contains("Parte") }
            return (listOf(details) + parts).map { strip(it?.path("text")?.asText("") ?: "") }
        }

        /** nanoka `chara_info.stories` object keyed "0".."4" → [detalhes, parte1..4]. */
        private fun nanStories(detail: JsonNode?): List<String> {
            val stories = detail?.path("chara_info")?.path("stories") ?: return emptyList()
            if (stories.isMissingNode || (0..4).all { stories.path(it.toString()).asText("").isBlank() }) return emptyList()
            return (0..4).map { strip(stories.path(it.toString()).asText("")) }
        }

        // ---------------- relics / ornaments (srs only) ---------------- //

        internal fun buildReliquia(entry: JsonNode, detail: JsonNode?): Reliquia {
            val bonuses = setBonuses(entry)
            val pieces = detail?.let { children(it.path("pieces")) }.orEmpty()
            return Reliquia(
                nome = strip(entry.path("name").asText("")),
                efeito2Pecas = bonuses[2],
                efeito4Pecas = bonuses[4],
                cabeca = piece(pieces, 0),
                maos = piece(pieces, 1),
                corpo = piece(pieces, 2),
                pes = piece(pieces, 3),
            )
        }

        internal fun buildOrnamento(entry: JsonNode, detail: JsonNode?): OrnamentoPlano {
            val bonuses = setBonuses(entry)
            val pieces = detail?.let { children(it.path("pieces")) }.orEmpty()
            return OrnamentoPlano(
                nome = strip(entry.path("name").asText("")),
                efeito2Pecas = bonuses[2],
                esfera = piece(pieces, 0),
                corda = piece(pieces, 1),
            )
        }

        /** Set bonuses keyed by piece count (2/4), from the index entry's `skills` (desc + params). */
        private fun setBonuses(entry: JsonNode): Map<Int, String?> =
            children(entry.path("skills")).associate { sk ->
                sk.path("useNum").asInt() to fill(sk.path("desc").asText(""), srsParams(sk.path("params"))).ifBlank { null }
            }

        /** A relic-detail piece → name + lore (falling back to the shorter miniLore). */
        private fun piece(pieces: List<JsonNode>, i: Int): NamedText {
            val p = pieces.getOrNull(i) ?: return NamedText.EMPTY
            val descRaw = p.path("lore").asText("").ifBlank { p.path("miniLore").asText("") }
            return NamedText(strip(p.path("name").asText("")).ifBlank { null }, strip(descRaw).ifBlank { null })
        }

        // ---------------- light cones ---------------- //

        internal fun buildSrsCone(coneGameId: String, detail: JsonNode): ConeDeLuz {
            val skill = detail.path("skill")
            return ConeDeLuz(
                coneGameId = coneGameId,
                nome = strip(detail.path("name").asText("")),
                caminho = strip(detail.path("baseType").path("name").asText("")).ifBlank { null },
                raridade = detail.path("rarity").asInt(0).takeIf { it > 0 },
                efeitoNome = strip(skill.path("name").asText("")).ifBlank { null },
                efeitoDescricao = fill(skill.path("descHash").asText(""), srsMaxLevel(skill.path("levelData"))).ifBlank { null },
                descricao = fill(detail.path("descHash").asText(""), emptyList()).ifBlank { null },
            )
        }

        internal fun buildNanCone(coneGameId: String, meta: JsonNode, detail: JsonNode?): ConeDeLuz {
            val ref = detail?.path("refinements")
            return ConeDeLuz(
                coneGameId = coneGameId,
                nome = strip(meta.path("en").asText("")),
                caminho = meta.path("baseType").asText("").ifBlank { null }?.let { PATH_EN[it] ?: it },
                raridade = RARITY_DIGIT.find(meta.path("rank").asText(""))?.value?.toIntOrNull(),
                efeitoNome = ref?.path("name")?.asText("")?.let(::strip)?.ifBlank { null },
                efeitoDescricao = ref?.let { fill(it.path("desc").asText(""), nanMaxLevel(it.path("level"))).ifBlank { null } },
                descricao = null,
            )
        }
    }
}
