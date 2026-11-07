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

package icu.h2l.login.auth.offline.service

import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import icu.h2l.login.auth.offline.util.ExtraUuidUtils
import java.util.*

class OfflineHyperZoneCredential(
    private val repository: OfflineAuthRepository,
    private val pendingRegistrations: PendingOfflineRegistrationManager,
    private val registrationName: String,
    private val normalizedName: String,
    private val knownUserUUID: UUID? = null,
    private val pendingRegistrationId: UUID? = null,
    private val passProfileCreateUuid: Boolean = false,
) : HyperZoneCredential {
    override val channelId: String = CHANNEL_ID
    override val credentialId: String = pendingRegistrationId?.toString() ?: normalizedName

    override fun getRegistrationName(): String = registrationName

    override fun getBoundUserUUID(): UUID? {
        return knownUserUUID ?: repository.getByName(effectiveNormalizedName())?.userUUID
    }

    override fun getSuggestedGameUuid(): UUID? {
        if (!passProfileCreateUuid) {
            return null
        }

        return ExtraUuidUtils.getNormalOfflineUUID(normalizedName)
    }

    override fun validateBind(userUUID: UUID): String? {
        val currentName = effectiveNormalizedName()
        val entry = repository.getByName(currentName)
        val currentUserUUID = entry?.userUUID
        if (currentUserUUID != null && currentUserUUID != userUUID) {
            return "离线认证凭证 $currentName 已绑定到其他 User: $currentUserUUID"
        }

        val existingByProfile = repository.getByProfileId(userUUID)
        if (existingByProfile != null && !existingByProfile.name.equals(currentName, ignoreCase = true)) {
            return "目标 User 已绑定其他离线认证记录: ${existingByProfile.name}"
        }

        if (currentUserUUID == null && pendingRegistrationId != null && pendingRegistrations.get(pendingRegistrationId) == null) {
            return "离线认证待绑定注册数据不存在或已失效，无法完成绑定"
        }

        if (currentUserUUID == null && pendingRegistrationId == null) {
            return "离线认证凭证缺少待绑定注册数据，无法完成绑定"
        }

        return null
    }

    override fun bind(userUUID: UUID): Boolean {
        val currentUserUUID = getBoundUserUUID()
        if (currentUserUUID == userUUID) {
            pendingRegistrationId?.let(pendingRegistrations::remove)
            return true
        }
        if (currentUserUUID != null) {
            return false
        }

        val registrationId = pendingRegistrationId ?: return false
        val pendingRegistration = pendingRegistrations.consume(registrationId) ?: return false
        val created = repository.create(
            name = pendingRegistration.normalizedName,
            passwordHash = pendingRegistration.passwordHash,
            hashFormat = pendingRegistration.hashFormat,
            userUUID = userUUID,
            email = pendingRegistration.email
        )
        if (created) {
            return true
        }

        pendingRegistrations.put(pendingRegistration)
        return repository.getByName(effectiveNormalizedName())?.userUUID == userUUID
    }

    /**
     * 只删除当前离线认证名称与 User 完全匹配的绑定。
     *
     * 禁止在这里删除 HZL User、删除或切换 InGameProfile，或触发重新建档。
     */
    override fun unbind(userUUID: UUID): Boolean {
        return repository.deleteByNameAndProfile(effectiveNormalizedName(), userUUID)
    }

    /**
     * 创建一个新名称的凭证副本，同时同步更新 [PendingOfflineRegistrationManager] 中的待绑定数据。
     *
     * 旧凭证保持不变；调用方应先销毁旧凭证，再提交返回的新凭证。
     */
    fun withNewName(newName: String): OfflineHyperZoneCredential {
        val newNormalizedName = newName.lowercase()
        pendingRegistrationId?.let { id ->
            pendingRegistrations.rename(id, newNormalizedName)
        }
        return OfflineHyperZoneCredential(
            repository = repository,
            pendingRegistrations = pendingRegistrations,
            registrationName = newName,
            normalizedName = newNormalizedName,
            knownUserUUID = knownUserUUID,
            pendingRegistrationId = pendingRegistrationId,
            passProfileCreateUuid = passProfileCreateUuid,
        )
    }

    override fun withRegistrationName(newRegistrationName: String): OfflineHyperZoneCredential {
        return withNewName(newRegistrationName)
    }

    @Suppress("unused")
    internal fun pendingRegistrationIdOrNull(): UUID? {
        return pendingRegistrationId
    }

    @Suppress("unused")
    internal fun matchesNormalizedName(candidate: String): Boolean {
        return effectiveNormalizedName().equals(candidate, ignoreCase = true)
    }

    private fun effectiveNormalizedName(): String {
        val registrationId = pendingRegistrationId ?: return normalizedName
        return pendingRegistrations.get(registrationId)?.normalizedName ?: normalizedName
    }

    companion object {
        private const val CHANNEL_ID = "offline"
    }
}
