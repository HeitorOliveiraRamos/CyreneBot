package com.cyrene.conversation

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Persistence-only access to a user's remembered facts and opt-in flag.
 *
 * Deliberately depends on nothing but the JPA repositories. The fact-saving tool
 * ([com.cyrene.discord.tools.UserMemoryTools]) is auto-discovered by the chat model, so it
 * must not transitively reach [com.cyrene.ai.PersonalitySummarizer]/`OllamaChatModel` —
 * doing so creates a bean dependency cycle. This thin store breaks that chain;
 * [UserProfileService] keeps the AI-backed summarization separately.
 */
@Service
class UserMemoryStore(
    private val profileRepository: UserProfileRepository,
    private val factRepository: UserFactRepository,
) {

    /** Saves a fact about the user. Returns false (saving nothing) when the user has not
     *  opted into memory. */
    @Transactional
    fun rememberFact(userId: String, content: String): Boolean {
        val profile = profileRepository.findByUserId(userId) ?: return false
        if (!profile.memoryEnabled) return false
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        factRepository.save(UserFact(userId = userId, content = trimmed.take(MAX_FACT_LENGTH)))
        return true
    }

    /** Most recent facts for a user, newest first. */
    fun recentFacts(userId: String, limit: Int): List<UserFact> =
        factRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))

    @Transactional
    fun wipeFacts(userId: String) {
        factRepository.deleteByUserId(userId)
    }

    companion object {
        const val MAX_FACT_LENGTH = 300
    }
}
