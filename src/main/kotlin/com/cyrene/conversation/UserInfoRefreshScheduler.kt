package com.cyrene.conversation

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Daily job that refreshes the cached user_info rows from JDA so name, highest role, and
 * permission flags stay in sync with reality without paying the lookup cost on every
 * mention. Personality summaries are not touched here — they have their own refresh
 * cadence inside [UserInfoService.incrementExchanges].
 *
 * Rows whose user is no longer in the corresponding guild are skipped (kept on disk in
 * case the user rejoins; could be pruned by a separate job later).
 */
@Component
class UserInfoRefreshScheduler(
    private val repository: UserInfoRepository,
    private val userInfoService: UserInfoService,
    private val jda: JDA,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${bot.user-info.refresh-cron:0 0 4 * * *}")
    fun refreshAll() {
        log.info("UserInfo refresh job starting")
        var page = 0
        var totalUpdated = 0
        var totalSkipped = 0
        while (true) {
            val slice = repository.findAll(PageRequest.of(page, PAGE_SIZE))
            if (slice.isEmpty) break
            for (info in slice.content) {
                if (info.guildId == UserInfoService.DM_GUILD) {
                    totalSkipped++
                    continue
                }
                val guild: Guild? = jda.getGuildById(info.guildId)
                if (guild == null) {
                    totalSkipped++
                    continue
                }
                val member = try {
                    guild.getMemberById(info.userId)
                        ?: guild.retrieveMemberById(info.userId).complete()
                } catch (e: Exception) {
                    log.debug("Skipping {}/{} — could not retrieve member: {}", info.userId, info.guildId, e.message)
                    null
                }
                if (member == null) {
                    totalSkipped++
                    continue
                }
                try {
                    userInfoService.refresh(member, guild)
                    totalUpdated++
                } catch (e: Exception) {
                    log.warn("Failed refreshing user_info row id={} ({}/{}) ", info.id, info.userId, info.guildId, e)
                }
            }
            if (!slice.hasNext()) break
            page++
        }
        log.info("UserInfo refresh job done: updated={} skipped={}", totalUpdated, totalSkipped)
    }

    private companion object {
        const val PAGE_SIZE = 200
    }
}
