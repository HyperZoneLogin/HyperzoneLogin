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

import icu.h2l.api.db.Profile
import icu.h2l.api.event.profile.ProfileAttachedEvent
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.database.DatabaseHelper
import icu.h2l.login.player.VelocityHyperZonePlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VelocityHyperZoneProfileService(
    private val databaseHelper: DatabaseHelper
) : HyperZoneProfileService {
    companion object {
        private const val PROFILE_INSERT_RETRIES = 3
        private const val REUUID_CREATE_RETRIES = 8
    }

    private val attachedProfiles = ConcurrentHashMap<HyperZonePlayer, UUID>()

    override fun getProfile(profileId: UUID): Profile? {
        return databaseHelper.getProfile(profileId)
    }

    override fun getAttachedProfile(player: HyperZonePlayer): Profile? {
        val profileId = attachedProfiles[player] ?: return null
        return databaseHelper.getProfile(profileId)
    }

    fun getAttachedProfileId(player: HyperZonePlayer): UUID? {
        return attachedProfiles[player]
    }

    override fun canResolve(profileId: UUID): Boolean {
        return databaseHelper.getProfile(profileId) != null
    }

    override fun resolve(profileId: UUID): Profile {
        return databaseHelper.getProfile(profileId)
            ?: throw IllegalStateException("未找到 Profile: $profileId")
    }

    override fun canCreate(userName: String, uuid: UUID?): Boolean {
        return getCreateBlockedReason(userName, uuid) == null
    }

    override fun create(userName: String, uuid: UUID?): Profile {
        val resolvedUuid = resolveRequestedUuid(userName, uuid)
        return createTrustedProfile(userName, resolvedUuid)
    }

    fun canCreateWithReUuid(userName: String): Boolean {
        return getReUuidBlockedReason(userName) == null
    }

    fun getRenameBlockedReason(userName: String, uuid: UUID? = null): String? {
        val resolvedUuid = resolveRequestedUuid(userName, uuid)
        return RenameProfileCreatePolicy.getBlockedReason(
            requestedName = userName,
            requestedUuid = resolvedUuid,
            existingByName = databaseHelper.getProfileByName(userName)
        )
    }

    fun getReUuidBlockedReason(
        userName: String,
        remapPrefix: String = HyperZoneLoginMain.getRemapConfig().prefix
    ): String? {
        val preferredUuid = ReUuidResolver.preferredUuid(userName, remapPrefix)
        val existingByName = databaseHelper.getProfileByName(userName) ?: return null
        return databaseHelper.validateTrustedProfileCreate(userName, preferredUuid)
            ?: "名称 $userName 已映射到现有 Profile: ${existingByName.id}"
    }

    fun createWithReUuid(
        userName: String,
        remapPrefix: String = HyperZoneLoginMain.getRemapConfig().prefix
    ): Profile {
        repeat(REUUID_CREATE_RETRIES) {
            val resolvedUuid = ReUuidResolver.resolve(
                userName = userName,
                remapPrefix = remapPrefix,
                hasNameConflict = databaseHelper.getProfileByName(userName) != null,
                isUuidTaken = { candidate -> databaseHelper.getProfileByUuid(candidate) != null }
            ) ?: throw IllegalStateException(getReUuidBlockedReason(userName, remapPrefix) ?: "名称 $userName 已被占用")
            runCatching {
                return createTrustedProfile(userName, resolvedUuid)
            }.getOrElse { throwable ->
                getCreateBlockedReason(userName, resolvedUuid)?.let { reason ->
                    if (databaseHelper.getProfileByName(userName) != null) {
                        throw IllegalStateException(reason)
                    }
                }
                if (throwable is IllegalStateException && throwable.message?.contains("名称 ") == true) {
                    throw throwable
                }
            }
        }

        throw IllegalStateException(
            getReUuidBlockedReason(userName, remapPrefix) ?: "玩家 $userName 注册失败，未能创建 Profile"
        )
    }

    private fun createTrustedProfile(userName: String, resolvedUuid: UUID): Profile {
        getCreateBlockedReason(userName, resolvedUuid)?.let { reason ->
            throw IllegalStateException(reason)
        }

        repeat(PROFILE_INSERT_RETRIES) {
            val profileId = UUID.randomUUID()
            if (databaseHelper.createProfile(profileId, userName, resolvedUuid)) {
                return databaseHelper.getProfile(profileId) ?: Profile(profileId, userName, resolvedUuid)
            }
        }

        throw IllegalStateException(getCreateBlockedReason(userName, resolvedUuid) ?: "玩家 $userName 注册失败，未能创建 Profile")
    }

    override fun attachProfile(player: HyperZonePlayer, profileId: UUID): Profile? {
        val profile = databaseHelper.getProfile(profileId) ?: return null
        attachedProfiles[player] = profile.id
        runCatching {
            HyperZoneLoginMain.getInstance().proxy.eventManager.fire(
                ProfileAttachedEvent(player, profile)
            ).join()
        }.onFailure { throwable ->
            HyperZoneLoginMain.getInstance().logger.error(
                "玩家 ${player.clientOriginalName} attach Profile 事件分发失败: ${throwable.message}",
                throwable
            )
        }
        (player as? VelocityHyperZonePlayer)?.onAttachedProfileAvailable()
        return profile
    }

    override fun attachVerifiedCredentialProfile(player: HyperZonePlayer): Profile? {
        getAttachedProfile(player)?.let { return it }

        val credentials = player.getSubmittedCredentials()
        if (credentials.isEmpty()) {
            throw IllegalStateException("玩家 ${player.clientOriginalName} 尚未提交任何认证凭证，无法完成 Profile 绑定")
        }

        val distinctProfileIds = credentials.mapNotNull { it.getBoundProfileId() }.distinct()
        if (distinctProfileIds.isEmpty()) {
            return null
        }

        if (distinctProfileIds.size != 1) {
            throw IllegalStateException(
                "玩家 ${player.clientOriginalName} 提交了多个冲突的 Profile 凭证: ${distinctProfileIds.joinToString()}"
            )
        }

        return attachProfile(player, distinctProfileIds.single())
            ?: throw IllegalStateException("玩家 ${player.clientOriginalName} 的凭证指向了不存在的 Profile: ${distinctProfileIds.single()}")
    }

    fun clear(player: HyperZonePlayer) {
        attachedProfiles.remove(player)
    }

    fun getCreateBlockedReason(userName: String, uuid: UUID? = null): String? {
        val resolvedUuid = resolveRequestedUuid(userName, uuid)
        return databaseHelper.validateTrustedProfileCreate(userName, resolvedUuid)
    }

    override fun bindSubmittedCredentials(player: HyperZonePlayer, profileId: UUID): Profile {
        val targetProfile = databaseHelper.getProfile(profileId)
            ?: throw IllegalStateException("未找到绑定码对应的 Profile: $profileId")
        val credentials = player.getSubmittedCredentials()
        if (credentials.isEmpty()) {
            throw IllegalStateException("玩家 ${player.clientOriginalName} 当前没有可绑定的凭证")
        }

        credentials.forEach { credential ->
            val boundProfileId = credential.getBoundProfileId()
            if (boundProfileId != null && boundProfileId != profileId) {
                throw IllegalStateException(
                    "凭证 ${credential.channelId}:${credential.credentialId} 已绑定到其他 Profile: $boundProfileId"
                )
            }

            credential.validateBind(profileId)?.let { reason ->
                throw IllegalStateException(reason)
            }
        }

        credentials.forEach { credential ->
            if (credential.getBoundProfileId() == profileId) {
                return@forEach
            }

            if (!credential.bind(profileId)) {
                throw IllegalStateException("绑定凭证失败: ${credential.channelId}:${credential.credentialId}")
            }
        }

        return targetProfile
    }

    private fun resolveRequestedUuid(userName: String, uuid: UUID?): UUID {
        val remapPrefix = HyperZoneLoginMain.getRemapConfig().prefix
        return uuid ?: RemapUtils.genUUID(userName, remapPrefix)
    }
}

