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
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket
import icu.h2l.login.backend.hzlimbo.HzLimboServer
import icu.h2l.login.backend.hzlimbo.protocol.ConfigPackets
import icu.h2l.login.backend.hzlimbo.protocol.ProtocolVersions
import icu.h2l.login.backend.hzlimbo.world.RegistryCodec
import io.netty.buffer.ByteBuf

/**
 * configuration 阶段：下发 registry codec / tags，完成后进入 play。
 */
class ConfigHandler(
    private val connection: MinecraftConnection,
    private val server: HzLimboServer,
) : MinecraftSessionHandler {

    override fun activated() {
        val version = connection.protocolVersion
        val protocol = version.protocol

        if (protocol >= ProtocolVersions.P_1_20_5) {
            // 1.20.5+：先发 KnownPacks，等客户端回应后再发 registry + tags。
            connection.write(ConfigPackets.knownPacks(version))
        } else {
            // 1.20.2 ~ 1.20.4：整份 codec + finish configuration。
            connection.write(ConfigPackets.wholeCodecRegistrySync(version))
            connection.write(FinishedUpdatePacket.INSTANCE)
        }
    }

    override fun handle(knownPacks: KnownPacksPacket): Boolean {
        val version = connection.protocolVersion
        val codec = RegistryCodec.codec(version.protocol)
        for ((registryType, entries) in RegistryCodec.registries(codec)) {
            connection.write(ConfigPackets.registryData(version, registryType, entries))
        }
        connection.write(TagsUpdatePacket(RegistryCodec.tags(version.protocol)))
        connection.write(FinishedUpdatePacket.INSTANCE)
        return true
    }

    override fun handle(finished: FinishedUpdatePacket): Boolean {
        connection.setActiveSessionHandler(StateRegistry.PLAY, PlayHandler(connection, server))
        return true
    }

    override fun handleUnknown(buf: ByteBuf) {
        connection.close(true)
    }
}
