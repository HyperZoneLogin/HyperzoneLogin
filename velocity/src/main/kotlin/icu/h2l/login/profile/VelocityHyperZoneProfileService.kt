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
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneProfileResolveResult
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
    private val attachedProfiles = ConcurrentHashMap<HyperZonePlayer, UUID>()

    override fun getProfile(profileId: UUID): Profile? {
        return databaseHelper.getProfile(profileId)
    }

    override fun getAttachedProfile(player: HyperZonePlayer): Profile? {
        val profileId = attachedProfiles[player] ?: return null
        return databaseHelper.getProfile(profileId)
    }

    override fun canResolveOrCreateProfile(userName: String, uuid: UUID?): Boolean {
        val resolvedUuid = resolveRequestedUuid(userName, uuid)
        val resolved = databaseHelper.resolveTrustedProfile(userName, resolvedUuid)
        return resolved.reason == null
    }

    override fun tryResolveOrCreateProfile(userName: String, uuid: UUID?): HyperZoneProfileResolveResult {
        val resolvedUuid = resolveRequestedUuid(userName, uuid)
        val resolved = databaseHelper.resolveOrCreateTrustedProfile(userName, resolvedUuid)
        return HyperZoneProfileResolveResult(
            profile = resolved.profile,
            created = resolved.created,
            reason = resolved.reason
        )
    }

    override fun resolveOrCreateProfile(player: HyperZonePlayer, userName: String?, uuid: UUID?): Profile {
        getAttachedProfile(player)?.let { return it }

        val resolvedName = userName ?: player.clientOriginalName
        val resolved = tryResolveOrCreateProfile(resolvedName, uuid)
        return resolved.profile
            ?: throw IllegalStateException(resolved.reason ?: "玩家 $resolvedName 注册失败，未能解析 Profile")
    }

    override fun attachProfile(player: HyperZonePlayer, profileId: UUID): Profile? {
        val profile = databaseHelper.getProfile(profileId) ?: return null
        attachedProfiles[player] = profile.id
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

