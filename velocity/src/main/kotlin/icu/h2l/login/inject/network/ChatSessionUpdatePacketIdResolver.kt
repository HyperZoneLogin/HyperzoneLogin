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

package icu.h2l.login.inject.network

import com.velocitypowered.api.network.ProtocolVersion
import icu.h2l.login.reflect.ProtocolVersionCompat
import icu.h2l.login.reflect.ProtocolVersionCompat.noGreaterThanCompat
import icu.h2l.login.reflect.ProtocolVersionCompat.noLessThanCompat

object ChatSessionUpdatePacketIdResolver {
    fun resolve(protocolVersion: ProtocolVersion): Int? {
        return when {
//            ViaVersion - CHAT_SESSION_UPDATE
            protocolVersion == ProtocolVersion.MINECRAFT_1_19_3 -> 0x20

            protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_19_4)
                    && protocolVersion.noGreaterThan(ProtocolVersion.MINECRAFT_1_20_3) -> 0x06

//            1.20.5-1.21.1
            protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_20_5)
                    && protocolVersion.noGreaterThan(
                ProtocolVersion.MINECRAFT_1_21
            ) -> 0x07

            // 1.21.2 ≤ v < 1.21.6 → 0x08
            protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_21_2)
                    && protocolVersion.noGreaterThan(
                ProtocolVersion.MINECRAFT_1_21_5
            ) -> 0x08

            // 1.21.6 ≤ v < 26.1 → 0x09
            protocolVersion.noLessThan(
                ProtocolVersion.MINECRAFT_1_21_6
            ) && protocolVersion.noGreaterThan(
                ProtocolVersion.MINECRAFT_1_21_11
            ) -> 0x09

            // v ≥ 26.1 → 0x0A
            protocolVersion.noLessThanCompat(
                ProtocolVersionCompat.MINECRAFT_26_1,
                ProtocolVersionCompat.RAW_26_1,
            ) -> 0x0A

            else -> null
        }
    }
}
