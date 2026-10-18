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

package icu.h2l.login.backend.hzlimbo.protocol

import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.proxy.protocol.ProtocolUtils
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket
import icu.h2l.login.backend.hzlimbo.world.RegistryCodec
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.kyori.adventure.nbt.BinaryTag
import net.kyori.adventure.nbt.CompoundBinaryTag

/**
 * configuration 阶段用到的 limbo 专属 clientbound 包（原始字节构造）。
 *
 * `RegistrySyncPacket` 是 Velocity 里的原始字节透传包（DeferredByteBufHolder），直接复用其
 * 在 StateRegistry.CONFIG 里注册的包 id（1.20.2 ~ 1.20.4 为 0x05，1.20.5+ 为 0x07）。
 */
object ConfigPackets {

    /**
     * 1.20.5+ 的 KnownPacks 请求：告诉客户端「我们只认 minecraft:core」。
     */
    fun knownPacks(version: ProtocolVersion): ByteBuf {
        val buf = Unpooled.buffer()
        val id = if (version.protocol >= ProtocolVersions.P_26_3) 0x0F else 0x0E
        ProtocolUtils.writeVarInt(buf, id)
        ProtocolUtils.writeVarInt(buf, 1)
        ProtocolUtils.writeString(buf, "minecraft")
        ProtocolUtils.writeString(buf, "core")
        ProtocolUtils.writeString(buf, version.versionIntroducedIn)
        return buf
    }

    /**
     * 1.20.2 ~ 1.20.4：整份 registry codec 作为单个 registry data 包下发。
     */
    fun wholeCodecRegistrySync(version: ProtocolVersion, codec: CompoundBinaryTag): RegistrySyncPacket {
        val buf = Unpooled.buffer()
        ProtocolUtils.writeBinaryTag(buf, version, codec)
        return RegistrySyncPacket().also { it.replace(buf) }
    }

    /**
     * 1.20.5+：单个 registry 的 registry data 包（registryType + 条目列表）。
     */
    fun registryData(
        version: ProtocolVersion,
        registryType: String,
        entries: List<Pair<String, BinaryTag?>>,
    ): RegistrySyncPacket {
        val buf = Unpooled.buffer()
        ProtocolUtils.writeString(buf, registryType)
        ProtocolUtils.writeVarInt(buf, entries.size)
        for ((name, element) in entries) {
            ProtocolUtils.writeString(buf, name)
            if (element != null) {
                buf.writeBoolean(true)
                ProtocolUtils.writeBinaryTag(buf, version, element)
            } else {
                buf.writeBoolean(false)
            }
        }
        return RegistrySyncPacket().also { it.replace(buf) }
    }

    /** 依据协议号取整份 codec 并下发（1.20.2 ~ 1.20.4）。 */
    fun wholeCodecRegistrySync(version: ProtocolVersion): RegistrySyncPacket =
        wholeCodecRegistrySync(version, RegistryCodec.codec(version.protocol))
}
