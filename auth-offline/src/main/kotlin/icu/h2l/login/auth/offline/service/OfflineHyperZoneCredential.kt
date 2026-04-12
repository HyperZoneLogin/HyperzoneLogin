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
import java.util.UUID

class OfflineHyperZoneCredential(
    private val repository: OfflineAuthRepository,
    private val pendingRegistrations: PendingOfflineRegistrationManager,
    private val normalizedName: String,
    private val knownProfileId: UUID? = null,
    private val pendingRegistrationId: UUID? = null
) : HyperZoneCredential {
    override val channelId: String = CHANNEL_ID
    override val credentialId: String = pendingRegistrationId?.let { "$normalizedName:$it" } ?: normalizedName

    override fun getBoundProfileId(): UUID? {
        return knownProfileId ?: repository.getByName(normalizedName)?.profileId
    }

    override fun validateBind(profileId: UUID): String? {
        val entry = repository.getByName(normalizedName)
        val currentProfileId = entry?.profileId
        if (currentProfileId != null && currentProfileId != profileId) {
            return "离线认证凭证 $normalizedName 已绑定到其他 Profile: $currentProfileId"
        }

        val existingByProfile = repository.getByProfileId(profileId)
        if (existingByProfile != null && !existingByProfile.name.equals(normalizedName, ignoreCase = true)) {
            return "目标 Profile 已绑定其他离线认证记录: ${existingByProfile.name}"
        }

        if (currentProfileId == null && pendingRegistrationId != null && pendingRegistrations.get(pendingRegistrationId) == null) {
            return "离线认证待绑定注册数据不存在或已失效，无法完成绑定"
        }

        if (currentProfileId == null && pendingRegistrationId == null) {
            return "离线认证凭证缺少待绑定注册数据，无法完成绑定"
        }

        return null
    }

    override fun bind(profileId: UUID): Boolean {
        val currentProfileId = getBoundProfileId()
        if (currentProfileId == profileId) {
            pendingRegistrationId?.let(pendingRegistrations::remove)
            return true
        }
        if (currentProfileId != null) {
            return false
        }

        val registrationId = pendingRegistrationId ?: return false
        val pendingRegistration = pendingRegistrations.consume(registrationId) ?: return false
        val created = repository.create(
            name = pendingRegistration.normalizedName,
            passwordHash = pendingRegistration.passwordHash,
            hashFormat = pendingRegistration.hashFormat,
            profileId = profileId,
            email = pendingRegistration.email
        )
        if (created) {
            return true
        }

        pendingRegistrations.put(pendingRegistration)
        return repository.getByName(normalizedName)?.profileId == profileId
    }

    @Suppress("unused")
    internal fun pendingRegistrationIdOrNull(): UUID? {
        return pendingRegistrationId
    }

    @Suppress("unused")
    internal fun matchesNormalizedName(candidate: String): Boolean {
        return normalizedName.equals(candidate, ignoreCase = true)
    }

    companion object {
        private const val CHANNEL_ID = "offline"
    }
}


