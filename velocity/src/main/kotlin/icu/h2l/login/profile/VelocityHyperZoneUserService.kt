/*
 * This file is part of HyperZoneLogin, licensed under the GNU Affero General Public License v3.0 or later.
 *
 * Copyright (C) ksqeib (庆灵) <ksqeib@qq.com>
 * Copyright (C) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package icu.h2l.login.profile

import icu.h2l.api.db.InGameProfile
import icu.h2l.api.db.InGameProfileCandidate
import icu.h2l.api.db.User
import icu.h2l.api.event.profile.UserAttachedEvent
import icu.h2l.api.log.warn
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.api.profile.InGameProfileConflictException
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.database.helper.InGameProfileDatabaseHelper
import icu.h2l.login.database.helper.UserDatabaseHelper
import icu.h2l.login.player.VelocityHyperZonePlayer
import icu.h2l.login.database.helper.UserRow
import icu.h2l.login.util.debugOutPreTrace
import java.util.*

/**
 * Velocity 侧账号/游戏内身份服务。
 *
 * 数据库层只提供两个明确职责的 helper：
 * - [UserDatabaseHelper]：只读写 profile 表；
 * - [InGameProfileDatabaseHelper]：只读写 in_game_profile 表。
 *
 * User 与 InGameProfile 的协调、冲突判断与活动身份解析集中在本服务中。
 */
class VelocityHyperZoneUserService(
    private val userDatabaseHelper: UserDatabaseHelper,
    private val inGameProfileDatabaseHelper: InGameProfileDatabaseHelper,
) : HyperZoneUserService {
    companion object {
        private const val PROFILE_INSERT_RETRIES = 3
    }

    override fun getUser(userUUID: UUID): User? {
        val row = userDatabaseHelper.getRow(userUUID) ?: return null
        return User(
            id = row.id,
            activeInGameProfileId = row.activeInGameProfileId,
        )
    }

    override fun getUserByName(name: String): User? {
        val matched = inGameProfileDatabaseHelper.listByNameLower(name.trim().lowercase(Locale.ROOT))
        val distinctUserUUIDs = matched.map { it.userUUID }.distinct()
        if (distinctUserUUIDs.size > 1) {
            warn { "名称 $name 同时映射到多个 User: $distinctUserUUIDs，取第一条" }
        }
        val userUUID = distinctUserUUIDs.firstOrNull() ?: return null
        return getUser(userUUID)
    }

    override fun getUserByGameUuid(playerUuid: UUID): User? {
        val matched = inGameProfileDatabaseHelper.listByUuid(playerUuid)
        val distinctUserUUIDs = matched.map { it.userUUID }.distinct()
        if (distinctUserUUIDs.size > 1) {
            warn { "UUID $playerUuid 同时映射到多个 User: $distinctUserUUIDs，取第一条" }
        }
        val userUUID = distinctUserUUIDs.firstOrNull() ?: return null
        return getUser(userUUID)
    }

    override fun getAttachedUser(player: HyperZonePlayer): User? {
        return player.attachedUser
    }

    override fun canResolveUser(userUUID: UUID): Boolean {
        return getUser(userUUID) != null
    }

    override fun resolveUser(userUUID: UUID): User {
        return getUser(userUUID)
            ?: throw IllegalStateException("未找到 User: $userUUID")
    }

    override fun canCreateUser(credential: HyperZoneCredential): Boolean {
        return getCreateBlockedReason(resolveProfileCreationCandidate(credential)) == null
    }

    /**
     * 创建 HZL User 及其初始 InGameProfile。
     *
     * 初始身份始终使用 HZL 前缀生成的游戏 UUID，绝不直接采用渠道上报 UUID。
     * 渠道提供的名称/UUID 由后续登录 attach 流程作为独立 InGameProfile 记录。
     */
    override fun createUser(credential: HyperZoneCredential): User {
        val candidate = resolveProfileCreationCandidate(credential)
        val resolvedUuid = candidate.uuid ?: defaultGameUuid(candidate.name)
        return createTrustedUser(candidate.copy(uuid = resolvedUuid), resolvedUuid)
    }

    fun canCreateUser(userName: String, uuid: UUID? = null): Boolean {
        return getCreateBlockedReason(userName, uuid) == null
    }

    fun createUser(userName: String, uuid: UUID? = null): User {
        val resolvedUuid = resolveRequestedUuid(userName, uuid)
        return createTrustedUser(
            InGameProfileCandidate(
                channel = "hzl",
                name = userName,
                uuid = resolvedUuid,
            ),
            resolvedUuid,
        )
    }

    fun getRenameBlockedReason(userName: String, uuid: UUID? = null): String? {
        val resolvedUuid = resolveRequestedUuid(userName, uuid)
        return validateInGameProfileCandidate(
            userUUID = null,
            candidate = InGameProfileCandidate(
                channel = "hzl",
                name = userName,
                uuid = resolvedUuid,
            ),
        )
    }

    private fun createTrustedUser(candidate: InGameProfileCandidate, resolvedUuid: UUID): User {
        val normalizedCandidate = candidate.copy(name = candidate.name.trim(), uuid = resolvedUuid)
        getCreateBlockedReason(normalizedCandidate)?.let { reason ->
            throw IllegalStateException(reason)
        }

        var lastFailure: Throwable? = null
        repeat(PROFILE_INSERT_RETRIES) {
            val userUUID = UUID.randomUUID()
            try {
                return createUserWithInitialIdentity(userUUID, normalizedCandidate, resolvedUuid)
            } catch (throwable: Throwable) {
                lastFailure = throwable
            }
        }

        throw IllegalStateException(
            getCreateBlockedReason(normalizedCandidate) ?: "玩家 ${normalizedCandidate.name} 注册失败，未能创建 User",
            lastFailure,
        )
    }

    private fun createUserWithInitialIdentity(
        userUUID: UUID,
        candidate: InGameProfileCandidate,
        resolvedUuid: UUID,
    ): User {
        try {
            userDatabaseHelper.insert(userUUID)
            val initial = inGameProfileDatabaseHelper.insert(userUUID, candidate.name, resolvedUuid)
            userDatabaseHelper.setActiveInGameProfileId(userUUID, initial.id)

            val user = User(
                id = userUUID,
                activeInGameProfileId = initial.id,
            )
            return user
        } catch (throwable: Throwable) {
            runCatching { userDatabaseHelper.delete(userUUID) }
            throw if (throwable is IllegalStateException) {
                throwable
            } else {
                IllegalStateException("创建档案失败: ${throwable.message}", throwable)
            }
        }
    }

    override fun attachUser(player: HyperZonePlayer, userUUID: UUID): User? {
        val user = getUser(userUUID) ?: return null
        val activeIdentity = getActiveInGameProfile(user.id)
        (player as? VelocityHyperZonePlayer)?.bindAttachedUser(user, activeIdentity)

        debugOutPreTrace {
            "userService.attachUser player=${player.getProxyPlayerOrNull()?.username ?: user.id} userUUID=${user.id} userName=${activeIdentity?.name} userGameUuid=${activeIdentity?.uuid}"
        }
        HyperZoneLoginMain.getInstance().proxy.eventManager.fire(UserAttachedEvent(player, user))
        return user
    }

    override fun resolveAuthenticatedUser(player: HyperZonePlayer): User? {
        getAttachedUser(player)?.let { return it }

        val credentials = player.getSubmittedCredentials()
        debugOutPreTrace {
            "userService.resolveAuthenticatedUser player=${player.getProxyPlayerOrNull()?.username ?: "<unbound>"} credentials=${credentials.map { "${it.javaClass.simpleName}:${it.getBoundUserUUID()}" }}"
        }
        if (credentials.isEmpty()) {
            throw IllegalStateException("玩家 ${player.getProxyPlayerOrNull()?.username ?: "<unbound>"} 尚未提交任何认证凭证，无法解析 HZL User")
        }

        val distinctUserUUIDs = credentials.mapNotNull { it.getBoundUserUUID() }.distinct()
        if (distinctUserUUIDs.isEmpty()) {
            return null
        }
        if (distinctUserUUIDs.size != 1) {
            throw IllegalStateException(
                "玩家 ${player.getProxyPlayerOrNull()?.username ?: "<unbound>"} 提交了多个冲突的 User 凭证: ${distinctUserUUIDs.joinToString()}"
            )
        }

        return getUser(distinctUserUUIDs.single())
            ?: throw IllegalStateException("玩家 ${player.getProxyPlayerOrNull()?.username ?: "<unbound>"} 的凭证指向了不存在的 User: ${distinctUserUUIDs.single()}")
    }

    fun clear(player: HyperZonePlayer) {
        (player as? VelocityHyperZonePlayer)?.clearUserCache()
    }

    fun getCreateBlockedReason(userName: String, uuid: UUID? = null): String? {
        val resolvedUuid = resolveRequestedUuid(userName, uuid)
        return getCreateBlockedReason(
            InGameProfileCandidate(channel = "hzl", name = userName, uuid = resolvedUuid)
        )
    }

    private fun getCreateBlockedReason(candidate: InGameProfileCandidate): String? {
        return validateInGameProfileCandidate(userUUID = null, candidate = candidate)
    }

    override fun bindSubmittedCredentials(player: HyperZonePlayer, userUUID: UUID): User {
        val targetUser = getUser(userUUID)
            ?: throw IllegalStateException("未找到绑定码对应的 User: $userUUID")
        val credentials = player.getSubmittedCredentials()
        if (credentials.isEmpty()) {
            throw IllegalStateException("玩家 ${player.logLabel} 当前没有可绑定的凭证")
        }

        credentials.forEach { credential ->
            val boundUserUUID = credential.getBoundUserUUID()
            if (boundUserUUID != null && boundUserUUID != userUUID) {
                throw IllegalStateException(
                    "凭证 ${credential.channelId}:${credential.credentialId} 已绑定到其他 User: $boundUserUUID"
                )
            }

            credential.validateBind(userUUID)?.let { reason ->
                throw IllegalStateException(reason)
            }
        }

        credentials.forEach { credential ->
            if (credential.getBoundUserUUID() == userUUID) {
                return@forEach
            }
            if (!credential.bind(userUUID)) {
                throw IllegalStateException("绑定凭证失败: ${credential.channelId}:${credential.credentialId}")
            }
        }

        credentials.forEach { credential ->
            credential.onConsumed(userUUID)
        }

        return getUser(userUUID) ?: targetUser
    }

    override fun getInGameProfiles(userUUID: UUID): List<InGameProfile> {
        return inGameProfileDatabaseHelper.listByProfileId(userUUID)
    }

    override fun getInGameProfile(inGameProfileId: UUID): InGameProfile? {
        return inGameProfileDatabaseHelper.getById(inGameProfileId)
    }

    override fun getActiveInGameProfile(userUUID: UUID): InGameProfile? {
        val row = userDatabaseHelper.getRow(userUUID) ?: return null
        return resolveActiveInGameProfile(row)
    }

    override fun findInGameProfileByUuid(uuid: UUID): InGameProfile? {
        return inGameProfileDatabaseHelper.findByUuid(uuid)
    }

    override fun createInGameProfile(userUUID: UUID, candidate: InGameProfileCandidate): InGameProfile {
        userDatabaseHelper.getRow(userUUID)
            ?: throw IllegalStateException("未找到 User: $userUUID")

        val resolvedUuid = candidate.uuid ?: defaultGameUuid(candidate.name)
        validateInGameProfileCandidate(userUUID, candidate.copy(uuid = resolvedUuid))?.let { reason ->
            throw InGameProfileConflictException(reason)
        }

        val created = inGameProfileDatabaseHelper.insert(userUUID, candidate.name, resolvedUuid)
        if (userDatabaseHelper.getActiveInGameProfileId(userUUID) == null) {
            userDatabaseHelper.setActiveInGameProfileId(userUUID, created.id)
        }
        return created
    }

    override fun switchActiveInGameProfile(userUUID: UUID, inGameProfileId: UUID): InGameProfile {
        val target = inGameProfileDatabaseHelper.getById(inGameProfileId)
            ?: throw IllegalStateException("未找到游戏内身份记录: $inGameProfileId")
        if (target.userUUID != userUUID) {
            throw IllegalStateException("游戏内身份 $inGameProfileId 不属于 User $userUUID")
        }
        userDatabaseHelper.setActiveInGameProfileId(userUUID, target.id)
        return target
    }

    override fun validateInGameProfileCandidate(
        userUUID: UUID?,
        candidate: InGameProfileCandidate,
    ): String? {
        val trimmedName = candidate.name.trim()
        if (trimmedName.isEmpty()) {
            return "游戏内名称不能为空"
        }

        val resolvedUuid = candidate.uuid ?: defaultGameUuid(trimmedName)
        val uuidOwner = inGameProfileDatabaseHelper.listByUuid(resolvedUuid).firstOrNull()
        if (uuidOwner != null) {
            return "UUID $resolvedUuid 已被使用: userUUID=${uuidOwner.userUUID}"
        }

        val nameOwner = inGameProfileDatabaseHelper.listByNameLower(trimmedName.lowercase(Locale.ROOT))
            .firstOrNull { it.userUUID != userUUID }
        if (nameOwner != null) {
            return "名称 $trimmedName 已属于其他 User: ${nameOwner.userUUID}"
        }

        return null
    }

    override fun defaultGameUuid(name: String): UUID {
        return RemapUtils.genUUID(name, HyperZoneLoginMain.getCoreConfig().remap.prefix)
    }

    private fun resolveProfileCreationCandidate(credential: HyperZoneCredential): InGameProfileCandidate {
        val name = credential.getRegistrationName()
            ?: throw IllegalStateException(
                "凭证 ${credential.channelId}:${credential.credentialId} 未提供注册名，无法创建 HZL User"
            )
        val useGeneratedUuid = HyperZoneLoginMain.getCoreConfig().remap.useHzlUuidAsDefaultIdentity
        val uuid = if (useGeneratedUuid) {
            defaultGameUuid(name)
        } else {
            resolveRequestedUuid(name, credential.getSuggestedGameUuid())
        }
        return InGameProfileCandidate(
            channel = credential.channelId,
            name = name,
            uuid = uuid,
        )
    }

    private fun resolveRequestedUuid(userName: String, uuid: UUID?): UUID {
        return uuid ?: defaultGameUuid(userName)
    }

    private fun resolveActiveInGameProfile(row: UserRow): InGameProfile? {
        val explicit = row.activeInGameProfileId?.let(inGameProfileDatabaseHelper::getById)
        if (explicit != null && explicit.userUUID == row.id) {
            return explicit
        }

        val existing = inGameProfileDatabaseHelper.listByProfileId(row.id)
        if (existing.isNotEmpty()) {
            val chosen = existing.first()
            if (row.activeInGameProfileId != chosen.id) {
                userDatabaseHelper.setActiveInGameProfileId(row.id, chosen.id)
            }
            return chosen
        }

        return null
    }
}
