package com.cyrene.moderation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WarningRepository : JpaRepository<Warning, Long> {
    fun countByUserIdAndGuildId(userId: String, guildId: String): Long
    fun deleteByUserIdAndGuildId(userId: String, guildId: String)
}
