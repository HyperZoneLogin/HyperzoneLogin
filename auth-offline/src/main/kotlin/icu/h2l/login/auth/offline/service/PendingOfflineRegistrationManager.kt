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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理“已注册但尚未绑定 Profile”的离线临时注册数据。
 *
 * 重要：这里保存的只是待绑定阶段的临时密码等数据，
 * 正式离线认证表只能在绑定成功后再创建记录，绝不能提前写入 `profileId = null` 的库数据。
 */
class PendingOfflineRegistrationManager {
    data class PendingOfflineRegistration(
        val credentialUuid: UUID,
        val normalizedName: String,
        val passwordHash: String,
        val hashFormat: String,
        val email: String? = null
    )

    private val registrations = ConcurrentHashMap<UUID, PendingOfflineRegistration>()

    fun put(registration: PendingOfflineRegistration): PendingOfflineRegistration {
        registrations[registration.credentialUuid] = registration
        return registration
    }

    fun get(credentialUuid: UUID): PendingOfflineRegistration? {
        return registrations[credentialUuid]
    }

    fun consume(credentialUuid: UUID): PendingOfflineRegistration? {
        return registrations.remove(credentialUuid)
    }

    fun remove(credentialUuid: UUID): PendingOfflineRegistration? {
        return registrations.remove(credentialUuid)
    }
}

