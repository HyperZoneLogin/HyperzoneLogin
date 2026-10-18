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
import com.velocitypowered.proxy.protocol.packet.StatusPingPacket
import com.velocitypowered.proxy.protocol.packet.StatusRequestPacket
import com.velocitypowered.proxy.protocol.packet.StatusResponsePacket
import icu.h2l.login.backend.hzlimbo.HzLimboServer
import io.netty.buffer.ByteBuf

/**
 * server list ping：固定返回状态，收到 ping 后回显并关闭连接。
 */
class StatusHandler(
    private val connection: MinecraftConnection,
    @Suppress("UNUSED_PARAMETER") private val server: HzLimboServer,
) : MinecraftSessionHandler {

    override fun handle(request: StatusRequestPacket): Boolean {
        connection.write(StatusResponsePacket(PingData.json(connection.protocolVersion.protocol)))
        return true
    }

    override fun handle(ping: StatusPingPacket): Boolean {
        connection.closeWith(ping)
        return true
    }

    override fun handleUnknown(buf: ByteBuf) {
        connection.close(true)
    }
}
