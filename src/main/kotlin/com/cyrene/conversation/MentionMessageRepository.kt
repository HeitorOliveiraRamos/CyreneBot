package com.cyrene.conversation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface MentionMessageRepository : JpaRepository<MentionMessage, Long> {

    /**
     * Drops rows older than [cutoff]. Used by the service to keep the table bounded.
     */
    @Modifying
    @Query("DELETE FROM MentionMessage m WHERE m.createdAt < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: OffsetDateTime): Int
}
