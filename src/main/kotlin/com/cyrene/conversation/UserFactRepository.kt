package com.cyrene.conversation

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface UserFactRepository : JpaRepository<UserFact, Long> {

    /** Most recent facts for a user, newest first. */
    fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable): List<UserFact>

    @Transactional
    fun deleteByUserId(userId: String): Int
}
