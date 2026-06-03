package com.cyrene.conversation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserProfileRepository : JpaRepository<UserProfile, Long> {

    fun findByUserId(userId: String): UserProfile?
}
