package com.cyrene.conversation

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserInfoRepository : JpaRepository<UserInfo, Long> {

    fun findByUserIdAndGuildId(userId: String, guildId: String): UserInfo?

    override fun findAll(pageable: Pageable): Page<UserInfo>
}
