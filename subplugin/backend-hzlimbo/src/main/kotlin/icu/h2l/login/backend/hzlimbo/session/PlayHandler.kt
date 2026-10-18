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
import com.velocitypowered.proxy.protocol.ProtocolUtils
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket
import icu.h2l.login.backend.hzlimbo.HzLimboServer
import icu.h2l.login.backend.hzlimbo.protocol.PlayPackets
import icu.h2l.login.backend.hzlimbo.protocol.ProtocolVersions
import io.netty.buffer.Unpooled
import java.util.concurrent.ThreadLocalRandom

/**
 * play 阶段：把玩家放进一个全空的虚空世界并保持连接。
 *
 * 与 NanoLimbo 一致，1.20.2 只发基础包（无 chunk），1.20.3+ 额外下发
 * start_waiting_for_chunks + 3x3 空 chunk。
 */
class PlayHandler(
    private val connection: MinecraftConnection,
    private val server: HzLimboServer,
) : MinecraftSessionHandler {

    private val teleportId: Int = ThreadLocalRandom.current().nextInt()

    override fun activated() {
        server.registerConnection(connection)

        val version = connection.protocolVersion
        val protocol = version.protocol

        connection.write(PlayPackets.joinGame(version, server.nextEntityId()))
        connection.write(PlayPackets.playerAbilities(version))
        connection.write(PlayPackets.playerPositionAndLook(version, teleportId))
        connection.write(PlayPackets.spawnPosition(version))
        connection.write(brandPacket())

        if (protocol >= ProtocolVersions.P_1_20_3) {
            connection.write(PlayPackets.startWaitingForChunks(version))
            for (chunkX in -1..1) {
                for (chunkZ in -1..1) {
                    connection.write(PlayPackets.emptyChunk(version, chunkX, chunkZ))
                }
            }
        }

        sendKeepAlive()
    }

    override fun handle(keepAlive: KeepAlivePacket): Boolean = true

    override fun disconnected() {
        server.unregisterConnection(connection)
    }

    private fun sendKeepAlive() {
        val keepAlive = KeepAlivePacket()
        keepAlive.randomId = ThreadLocalRandom.current().nextLong()
        connection.write(keepAlive)
    }

    private fun brandPacket(): PluginMessagePacket {
        val payload = Unpooled.buffer()
        ProtocolUtils.writeString(payload, BRAND_NAME)
        return PluginMessagePacket(BRAND_CHANNEL, payload)
    }

    companion object {
        private const val BRAND_CHANNEL = "minecraft:brand"
        private const val BRAND_NAME = "HyperZoneLogin"
    }
}
