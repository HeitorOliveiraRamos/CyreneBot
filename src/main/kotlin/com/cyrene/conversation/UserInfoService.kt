package com.cyrene.conversation

import com.cyrene.ai.PersonalitySummarizer
import com.cyrene.config.BotProperties
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Manages the cached per-(user, guild) profile used by the @-mention reply path and the
 * `/iniciar-conversa` chat session path.
 *
 * Why this exists: previously the brain pass had to call `getCallerInfo` and
 * `getCallerModerationPermissions` every turn to know who it was talking to and what they
 * could do. That worked but was unreliable (the brain doesn't always call tools) and slow
 * (extra LLM round-trips). Now the listener prefetches this info from JDA once on first
 * contact, stores it here, and a daily cron refreshes it.
 *
 * DM sessions are stored under the sentinel guild id [DM_GUILD]. DM rows have no role and
 * all permission flags false (Discord DMs have no moderation surface anyway). The daily
 * refresh job skips DM rows because there is no guild to re-fetch from.
 *
 * Permission flags are advisory; live moderation tools still re-verify via JDA.
 */
@Service
class UserInfoService(
    private val repository: UserInfoRepository,
    private val mentionRepository: MentionMessageRepository,
    private val summarizer: PersonalitySummarizer,
    private val properties: BotProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Fetches the cached profile for (user, guild), creating it on first contact from a
     * [Member] (full guild context: name, role, permissions).
     */
    @Transactional
    fun getOrCreate(member: Member, guild: Guild): UserInfo {
        repository.findByUserIdAndGuildId(member.id, guild.id)?.let { return it }
        val baseline = summarizer.generateBaseline(member)
        val info = UserInfo(
            userId = member.id,
            guildId = guild.id,
            effectiveName = member.effectiveName,
            personalitySummary = baseline,
        )
        applyFromMember(info, member)
        return saveOrFetch(info)
    }

    /**
     * Fetches/creates the cached profile for a DM session. Stored under the [DM_GUILD]
     * sentinel so the unique (user_id, guild_id) constraint keeps DM and guild profiles
     * separate without needing a second table.
     */
    @Transactional
    fun getOrCreateForDm(user: User): UserInfo {
        repository.findByUserIdAndGuildId(user.id, DM_GUILD)?.let { return it }
        val baseline = summarizer.generateBaseline(user)
        val info = UserInfo(
            userId = user.id,
            guildId = DM_GUILD,
            effectiveName = user.effectiveName,
            personalitySummary = baseline,
        )
        return saveOrFetch(info)
    }

    /**
     * Updates the cached name/role/permissions from JDA. Used by the daily cron.
     * Personality summary is left untouched here — it has its own refresh cadence.
     */
    @Transactional
    fun refresh(member: Member, guild: Guild) {
        val info = repository.findByUserIdAndGuildId(member.id, guild.id) ?: return
        applyFromMember(info, member)
        info.lastRefreshedAt = OffsetDateTime.now()
        repository.save(info)
    }

    /**
     * Bumps the exchanges counter and, when it crosses the configured threshold, re-runs
     * the personality summarizer over the user's recent exchanges. Called after a reply
     * is successfully delivered.
     */
    @Transactional
    fun incrementExchanges(userId: String, guildId: String) {
        val info = repository.findByUserIdAndGuildId(userId, guildId) ?: return
        info.exchangesCount += 1
        val threshold = properties.userInfo.personalityRefreshEveryExchanges
        if (threshold > 0 && info.exchangesCount % threshold == 0) {
            val sample = mentionRepository.findByUserIdAndGuildIdOrderByCreatedAtDesc(
                userId = userId,
                guildId = guildId,
                pageable = PageRequest.of(0, properties.userInfo.personalitySampleSize),
            )
            val updated = summarizer.refreshFromExchanges(info.personalitySummary, sample)
            if (updated.isNotBlank()) info.personalitySummary = updated
        }
        repository.save(info)
    }

    /**
     * Convenience for listeners: resolves the right profile from the event (guild Member
     * vs DM User), then assembles the full system prompt block — name reminder + rendered
     * profile — ready to be passed as `extraSystemPrompt` to the AI.
     *
     * Returns null if profile resolution fails (e.g., transient JDA error). Callers should
     * still send a reply in that case — the AI will just be missing the cached profile.
     *
     * Also returns the resolved (userId, guildId) so the caller can later call
     * [incrementExchanges] with the same identifiers (especially the DM sentinel).
     */
    fun resolveForEvent(event: MessageReceivedEvent): ResolvedUserInfo? {
        return try {
            if (event.isFromGuild) {
                val guild = event.guild
                val member = event.member ?: guild.retrieveMemberById(event.author.id).complete()
                val info = getOrCreate(member, guild)
                ResolvedUserInfo(info, event.author.id, guild.id)
            } else {
                val info = getOrCreateForDm(event.author)
                ResolvedUserInfo(info, event.author.id, DM_GUILD)
            }
        } catch (e: Exception) {
            log.warn("resolveForEvent failed for user={} channel={}", event.author.id, event.channel.id, e)
            null
        }
    }

    /**
     * Builds the full system prompt block to inject into a brain+voice call: a short
     * name reminder followed by the rendered profile.
     */
    fun assembleSystemPrompt(info: UserInfo): String {
        // The persona file's `{nome}` token is already substituted with this same name
        // by PromptBuilder before the voice pass runs, so the model sees concrete
        // greetings ("Oi, Léo. Diz aí.") rather than templates. We still emit this line
        // so the brain pass (which doesn't load the persona) knows who the caller is.
        val callerLine = "O usuário com quem você está falando se chama ${info.effectiveName}."
        val profile = renderForPrompt(info)
        return listOf(callerLine, profile).joinToString("\n\n")
    }

    /**
     * Renders the cached profile as a PT-BR system block to be appended to the bot's
     * prompt. Kept terse on purpose; this is metadata, not persona.
     */
    fun renderForPrompt(info: UserInfo): String = buildString {
        appendLine("## Sobre o usuário com quem você está falando")
        appendLine("- Nome: ${info.effectiveName}")
        info.highestRoleName?.let { appendLine("- Cargo mais alto: $it") }
        val perms = renderPermissions(info)
        if (perms != null) appendLine("- Permissões de moderação: $perms")
        if (info.guildId == DM_GUILD) appendLine("- Contexto: conversa privada (DM)")
        info.personalitySummary
            ?.takeIf { it.isNotBlank() }
            ?.let {
                appendLine("- Perfil: $it")
            }
    }.trimEnd()

    private fun renderPermissions(info: UserInfo): String? {
        if (info.guildId == DM_GUILD) return null
        val flags = buildList {
            if (info.administrator) add("administrator")
            if (info.kickMembers) add("kickMembers")
            if (info.banMembers) add("banMembers")
            if (info.moderateMembers) add("moderateMembers")
            if (info.manageMessages) add("manageMessages")
            if (info.manageChannel) add("manageChannel")
            if (info.manageGuild) add("manageGuild")
        }
        return if (flags.isEmpty()) "nenhuma" else flags.joinToString(", ")
    }

    private fun applyFromMember(info: UserInfo, member: Member) {
        info.effectiveName = member.effectiveName
        val highest = member.roles.maxByOrNull { it.position }
        info.highestRoleId = highest?.id
        info.highestRoleName = highest?.name
        info.administrator = member.hasPermission(Permission.ADMINISTRATOR)
        info.kickMembers = member.hasPermission(Permission.KICK_MEMBERS)
        info.banMembers = member.hasPermission(Permission.BAN_MEMBERS)
        info.moderateMembers = member.hasPermission(Permission.MODERATE_MEMBERS)
        info.manageMessages = member.hasPermission(Permission.MESSAGE_MANAGE)
        info.manageChannel = member.hasPermission(Permission.MANAGE_CHANNEL)
        info.manageGuild = member.hasPermission(Permission.MANAGE_SERVER)
    }

    private fun saveOrFetch(info: UserInfo): UserInfo {
        return try {
            repository.save(info)
        } catch (e: Exception) {
            log.debug("UserInfo create raced for {}/{}, fetching existing", info.userId, info.guildId, e)
            repository.findByUserIdAndGuildId(info.userId, info.guildId) ?: throw e
        }
    }

    companion object {
        /** Sentinel guild id for DM rows. Picked so it can never collide with a real
         *  Discord snowflake (snowflakes are all digits). */
        const val DM_GUILD = "@dm"
    }
}

/**
 * Result of [UserInfoService.resolveForEvent]: the cached profile plus the identifiers
 * the caller needs to later bump the exchanges counter.
 */
data class ResolvedUserInfo(
    val info: UserInfo,
    val userId: String,
    val guildId: String,
)
