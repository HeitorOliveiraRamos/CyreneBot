package com.cyrene.conversation

import com.cyrene.ai.PersonalitySummarizer
import com.cyrene.config.BotProperties
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Owns the bot's per-user memory. Two distinct kinds of data flow through here:
 *
 *  - **Live identity** (name, highest role, moderation permissions): read fresh from the
 *    JDA [Member]/User on every interaction and injected into the prompt. NEVER persisted —
 *    it varies per guild and is cheap to fetch, so there is nothing to cache.
 *
 *  - **Persisted memory** ([UserProfile.personalitySummary] + [UserFact]s): only kept when
 *    the user has explicitly opted in via `/memoria`. A profile row is created on first
 *    contact with [UserProfile.memoryEnabled] = false (it just holds the opt-in flag); the
 *    privacy boundary is that flag — while it is off no summary is generated, no fact is
 *    saved, and the prompt carries only the ephemeral live identity above.
 *
 * Moderation tool authority is independent of all of this: [com.cyrene.discord.tools.DiscordTools]
 * re-verifies caller permissions live against JDA before any destructive action.
 */
@Service
class UserProfileService(
    private val repository: UserProfileRepository,
    private val memoryStore: UserMemoryStore,
    private val mentionRepository: MentionMessageRepository,
    private val summarizer: PersonalitySummarizer,
    private val properties: BotProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Builds the full system block for an incoming message: live identity (always) plus the
     * persisted memory block (only when the user opted in). Returns null on transient JDA
     * failure — callers should still reply, just without the profile context.
     */
    fun resolveForEvent(event: MessageReceivedEvent): ResolvedUserInfo? {
        return try {
            val userId = event.author.id
            val effectiveName: String
            val identityBlock: String
            if (event.isFromGuild) {
                val guild = event.guild
                val member = event.member ?: guild.retrieveMemberById(userId).complete()
                effectiveName = member.effectiveName
                identityBlock = renderGuildIdentity(member)
            } else {
                effectiveName = event.author.effectiveName
                identityBlock = renderDmIdentity()
            }

            val profile = getOrCreateProfile(userId)
            val memoryEnabled = profile.memoryEnabled
            val memoryBlock = if (memoryEnabled) renderMemory(profile, effectiveName) else null

            val systemPrompt = listOfNotNull(
                "O usuário com quem você está falando se chama $effectiveName.",
                identityBlock,
                memoryBlock,
            ).joinToString("\n\n")

            ResolvedUserInfo(userId, effectiveName, systemPrompt, memoryEnabled)
        } catch (e: Exception) {
            log.warn("resolveForEvent failed for user={} channel={}", event.author.id, event.channel.id, e)
            null
        }
    }

    /**
     * Bumps the exchange counter and, on crossing the configured threshold, re-summarizes
     * the user's recent exchanges. No-op when memory is disabled or the user has no profile.
     */
    @Transactional
    fun incrementExchanges(userId: String) {
        val profile = repository.findByUserId(userId) ?: return
        if (!profile.memoryEnabled) return
        profile.exchangesCount += 1
        val threshold = properties.userInfo.personalityRefreshEveryExchanges
        if (threshold > 0 && profile.exchangesCount % threshold == 0) {
            val sample = mentionRepository.findByUserIdOrderByCreatedAtDesc(
                userId = userId,
                pageable = PageRequest.of(0, properties.userInfo.personalitySampleSize),
            )
            val updated = summarizer.refreshFromExchanges(profile.personalitySummary, sample)
            if (updated.isNotBlank()) profile.personalitySummary = updated
        }
        profile.updatedAt = OffsetDateTime.now()
        repository.save(profile)
    }

    fun isMemoryEnabled(userId: String): Boolean =
        repository.findByUserId(userId)?.memoryEnabled == true

    /**
     * Returns the user's profile, creating it with memory DISABLED on first contact. Every
     * user gets a row so the opt-in flag has a home; nothing personal is persisted until
     * they flip [UserProfile.memoryEnabled] on via `/memoria`.
     */
    fun getOrCreateProfile(userId: String): UserProfile {
        repository.findByUserId(userId)?.let { return it }
        return try {
            repository.save(UserProfile(userId = userId, memoryEnabled = false))
        } catch (e: Exception) {
            // Concurrent first contact raced us to the insert; re-fetch the winner.
            log.debug("Profile create raced for {}, fetching existing", userId, e)
            repository.findByUserId(userId) ?: throw e
        }
    }

    /** Opts the user in. Creates the profile row if it somehow doesn't exist yet. Idempotent. */
    @Transactional
    fun enableMemory(userId: String) {
        val profile = getOrCreateProfile(userId)
        if (!profile.memoryEnabled) {
            profile.memoryEnabled = true
            profile.updatedAt = OffsetDateTime.now()
            repository.save(profile)
        }
    }

    /** Opts the user out and wipes everything stored about them (summary + facts). */
    @Transactional
    fun disableMemory(userId: String) {
        memoryStore.wipeFacts(userId)
        val profile = repository.findByUserId(userId) ?: return
        profile.memoryEnabled = false
        profile.personalitySummary = null
        profile.exchangesCount = 0
        profile.updatedAt = OffsetDateTime.now()
        repository.save(profile)
    }

    private fun renderGuildIdentity(member: Member): String = buildString {
        appendLine("## Sobre o usuário com quem você está falando")
        appendLine("- Nome: ${member.effectiveName}")
        member.roles.maxByOrNull { it.position }?.let { appendLine("- Cargo mais alto: ${it.name}") }
        appendLine("- Permissões de moderação: ${renderPermissions(member)}")
    }.trimEnd()

    private fun renderDmIdentity(): String = buildString {
        appendLine("## Sobre o usuário com quem você está falando")
        appendLine("- Contexto: conversa privada (DM), sem cargos ou permissões de servidor")
    }.trimEnd()

    private fun renderPermissions(member: Member): String {
        val flags = MODERATION_PERMISSIONS.filter { member.hasPermission(it.first) }.map { it.second }
        return if (flags.isEmpty()) "nenhuma" else flags.joinToString(", ")
    }

    private fun renderMemory(profile: UserProfile, name: String): String? {
        val facts = memoryStore.recentFacts(profile.userId, MAX_FACTS_IN_PROMPT)
        val summary = profile.personalitySummary?.takeIf { it.isNotBlank() }
        if (summary == null && facts.isEmpty()) return null
        return buildString {
            appendLine("## O que você lembra sobre $name")
            summary?.let { appendLine("- Perfil: $it") }
            if (facts.isNotEmpty()) {
                appendLine("- Coisas que ele te pediu para lembrar:")
                // Oldest first so the list reads chronologically.
                facts.asReversed().forEach { appendLine("  - ${it.content}") }
            }
        }.trimEnd()
    }

    companion object {
        private const val MAX_FACTS_IN_PROMPT = 50

        private val MODERATION_PERMISSIONS = listOf(
            Permission.ADMINISTRATOR to "administrator",
            Permission.KICK_MEMBERS to "kickMembers",
            Permission.BAN_MEMBERS to "banMembers",
            Permission.MODERATE_MEMBERS to "moderateMembers",
            Permission.MESSAGE_MANAGE to "manageMessages",
            Permission.MANAGE_CHANNEL to "manageChannel",
            Permission.MANAGE_SERVER to "manageGuild",
        )
    }
}

/**
 * Result of [UserProfileService.resolveForEvent]: the live name (for `{nome}` substitution),
 * the assembled system block, and whether persisted memory is active (so the listener knows
 * whether to bump the exchange counter afterward).
 */
data class ResolvedUserInfo(
    val userId: String,
    val effectiveName: String,
    val systemPrompt: String,
    val memoryEnabled: Boolean,
)
