package com.cyrene.conversation

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface MentionMessageRepository : JpaRepository<MentionMessage, Long> {

    /**
     * Most recent exchanges for a (user, guild), newest first. Used by
     * [com.cyrene.conversation.UserInfoService] when re-summarizing a user's
     * personality from their recent question/answer history.
     */
    fun findByUserIdAndGuildIdOrderByCreatedAtDesc(
        userId: String,
        guildId: String,
        pageable: Pageable,
    ): List<MentionMessage>

    /**
     * Drops rows older than [cutoff]. Used by the service to keep the table bounded.
     */
    @Modifying
    @Query("DELETE FROM MentionMessage m WHERE m.createdAt < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: OffsetDateTime): Int
}
