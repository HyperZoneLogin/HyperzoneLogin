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

package icu.h2l.login.auth.offline.util

import icu.h2l.login.auth.offline.config.AuthOfflineConfigLoader
import icu.h2l.login.auth.offline.type.OfflineUUIDType
import icu.h2l.login.auth.offline.util.uuid.PCL2UUIDUtil
import java.nio.charset.StandardCharsets
import java.util.*

object ExtraUuidUtils {
    private val zero: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

    fun matchType(holderUUID: UUID?, name: String): OfflineUUIDType {
        if (holderUUID == null) {
            return OfflineUUIDType.ZERO
        }

        val cfg = AuthOfflineConfigLoader.getConfig().match
        return when {
            cfg.uuidMatch.offline && holderUUID == getNormalOfflineUUID(name) -> OfflineUUIDType.OFFLINE
            cfg.uuidMatch.pcl2.enable && PCL2UUIDUtil.isPCL2UUID(holderUUID, name) -> OfflineUUIDType.PCL
            cfg.uuidMatch.zero && holderUUID == zero -> OfflineUUIDType.ZERO
            else -> OfflineUUIDType.UNKNOWN
        }
     }

    fun getNormalOfflineUUID(username: String): UUID {
         return UUID.nameUUIDFromBytes(("OfflinePlayer:$username").toByteArray(StandardCharsets.UTF_8))
     }
 }


