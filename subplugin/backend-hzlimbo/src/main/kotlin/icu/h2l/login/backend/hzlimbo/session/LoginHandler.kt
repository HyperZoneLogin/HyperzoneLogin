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

import com.velocitypowered.api.util.UuidUtils
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.packet.LoginAcknowledgedPacket
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket
import icu.h2l.login.backend.hzlimbo.HzLimboServer
import icu.h2l.login.backend.hzlimbo.protocol.ProtocolVersions
import io.netty.buffer.ByteBuf
import java.util.UUID

/**
 * 登录阶段：离线模式直接发 LoginSuccess，等客户端 LoginAcknowledged 后进入 configuration。
 *
 * limbo 不做 infoForwarding，用户名不可信，UUID 直接按离线算法派生即可。
 */
class LoginHandler(
    private val connection: MinecraftConnection,
    private val server: HzLimboServer,
) : MinecraftSessionHandler {

    private var successSent = false

    override fun handle(login: ServerLoginPacket): Boolean {
        if (successSent) {
            return true
        }

        val username = login.username
        val success = ServerLoginSuccessPacket()
        success.uuid = UuidUtils.generateOfflinePlayerUuid(username)
        success.username = username
        success.properties = emptyList()
        if (connection.protocolVersion.protocol >= ProtocolVersions.P_26_2) {
            success.setSessionId(UUID.randomUUID())
        }

        connection.write(success)
        successSent = true
        return true
    }

    override fun handle(acknowledged: LoginAcknowledgedPacket): Boolean {
        if (!successSent) {
            connection.close(true)
            return true
        }
        connection.setActiveSessionHandler(StateRegistry.CONFIG, ConfigHandler(connection, server))
        return true
    }

    override fun handleUnknown(buf: ByteBuf) {
        connection.close(true)
    }
}
