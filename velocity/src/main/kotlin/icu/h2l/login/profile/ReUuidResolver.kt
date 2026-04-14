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

import icu.h2l.api.util.RemapUtils
import java.util.UUID

internal object ReUuidResolver {
    private const val RANDOM_UUID_ATTEMPTS = 32

    fun preferredUuid(userName: String, remapPrefix: String): UUID {
        return RemapUtils.genUUID(userName, remapPrefix)
    }

    fun resolve(
        userName: String,
        remapPrefix: String,
        hasNameConflict: Boolean,
        isUuidTaken: (UUID) -> Boolean,
        randomUuidSupplier: () -> UUID = UUID::randomUUID
    ): UUID? {
        if (hasNameConflict) {
            return null
        }

        val preferredUuid = preferredUuid(userName, remapPrefix)
        if (!isUuidTaken(preferredUuid)) {
            return preferredUuid
        }

        repeat(RANDOM_UUID_ATTEMPTS) {
            val randomUuid = randomUuidSupplier()
            if (!isUuidTaken(randomUuid)) {
                return randomUuid
            }
        }

        throw IllegalStateException("未能为名称 $userName 选出可用 UUID")
    }
}

