package com.cyrene.moderation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ModerationService(private val warnings: WarningRepository) {

    @Transactional
    fun addWarning(userId: String, guildId: String, reason: String? = null): Long {
        warnings.save(Warning(userId = userId, guildId = guildId, reason = reason))
        return warnings.countByUserIdAndGuildId(userId, guildId)
    }

    @Transactional
    fun clearWarnings(userId: String, guildId: String) {
        warnings.deleteByUserIdAndGuildId(userId, guildId)
    }
}
