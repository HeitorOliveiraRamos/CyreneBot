package com.cyrene.conversation

import com.cyrene.config.BotProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Persists @-mention exchanges. The reply path no longer feeds prior turns back into the
 * prompt (each mention is answered statelessly), but exchanges are still recorded so the
 * personality summarizer can periodically re-summarize the user from their recent
 * question/answer history.
 *
 * Old rows are pruned opportunistically on each write to keep the table bounded without
 * needing a dedicated cleanup job.
 */
@Service
class MentionContextService(
    private val repository: MentionMessageRepository,
    private val properties: BotProperties,
) {

    /**
     * Persists a full question/answer pair as a single row. Called only after the reply
     * has actually been produced.
     */
    @Transactional
    fun recordExchange(
        userId: String,
        guildId: String?,
        channelId: String,
        userMessage: String,
        assistantReply: String,
    ) {
        repository.save(
            MentionMessage(
                userId = userId,
                guildId = guildId,
                channelId = channelId,
                userMessage = userMessage,
                assistantReply = assistantReply,
            )
        )
        pruneOld()
    }

    private fun pruneOld() {
        val retention = properties.mentionContext.retentionHours
        if (retention <= 0) return
        val cutoff = OffsetDateTime.now().minus(Duration.ofHours(retention))
        repository.deleteOlderThan(cutoff)
    }
}
