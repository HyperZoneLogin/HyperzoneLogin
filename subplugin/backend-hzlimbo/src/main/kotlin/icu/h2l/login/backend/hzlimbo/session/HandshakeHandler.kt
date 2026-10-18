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

package icu.h2l.login.backend.hzlimbo.session

import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket
import com.velocitypowered.proxy.protocol.packet.HandshakePacket
import icu.h2l.login.backend.hzlimbo.HzLimboServer
import icu.h2l.login.backend.hzlimbo.protocol.ProtocolVersions
import io.netty.buffer.ByteBuf
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * 初始握手：按 intent 分流到 STATUS 或 LOGIN；仅接受 1.20.2+。
 */
class HandshakeHandler(
    private val connection: MinecraftConnection,
    private val server: HzLimboServer,
) : MinecraftSessionHandler {

    override fun handle(handshake: HandshakePacket): Boolean {
        connection.setProtocolVersion(handshake.protocolVersion)

        when (handshake.nextStatus) {
            StateRegistry.STATUS_ID -> {
                connection.setActiveSessionHandler(StateRegistry.STATUS, StatusHandler(connection, server))
            }

            StateRegistry.LOGIN_ID, StateRegistry.TRANSFER_ID -> {
                if (!ProtocolVersions.isSupported(handshake.protocolVersion.protocol)) {
                    connection.setState(StateRegistry.LOGIN)
                    connection.closeWith(
                        DisconnectPacket.create(
                            Component.text("Unsupported client version (1.20.2+ required)", NamedTextColor.RED),
                            handshake.protocolVersion,
                            StateRegistry.LOGIN,
                        ),
                    )
                } else {
                    connection.setActiveSessionHandler(StateRegistry.LOGIN, LoginHandler(connection, server))
                }
            }

            else -> connection.close(true)
        }
        return true
    }

    override fun handleUnknown(buf: ByteBuf) {
        connection.close(true)
    }
}
