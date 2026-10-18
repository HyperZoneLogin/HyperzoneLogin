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
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.LongArrayBinaryTag

/**
 * Velocity 无法程序化构造（或根本没有实现）的 limbo 专属 play 阶段 clientbound 包。
 *
 * 这些包以「原始字节（packet id + payload）」形式直接写入连接，绕过 [com.velocitypowered.proxy.protocol.netty.MinecraftEncoder]
 * 的包 id 映射（它对非 [com.velocitypowered.proxy.protocol.MinecraftPacket] 的 [ByteBuf] 直接透传），
 * 再由帧编码器补齐长度前缀。编码格式与包 id 映射均来自 NanoLimbo（已支持 1.20.2 ~ 26.3）。
 */
object PlayPackets {

    /** 虚空世界固定的 section 数（overworld 高度 384 / 16，1.18+ 至今未变）。 */
    private const val SECTIONS = 24

    private const val GAMEMODE_ADVENTURE = 2

    // ---------------------------------------------------------------------
    // packet id（clientbound, play 阶段）
    // ---------------------------------------------------------------------

    private fun joinGameId(p: Int): Int = when {
        p >= ProtocolVersions.P_26_3 -> 0x32
        p >= ProtocolVersions.P_26_1 -> 0x31
        p >= ProtocolVersions.P_1_21_9 -> 0x30
        p >= ProtocolVersions.P_1_21_5 -> 0x2B
        p >= ProtocolVersions.P_1_21_2 -> 0x2C
        p >= ProtocolVersions.P_1_20_5 -> 0x2B
        else -> 0x29
    }

    private fun abilitiesId(p: Int): Int = when {
        p >= ProtocolVersions.P_26_3 -> 0x41
        p >= ProtocolVersions.P_26_1 -> 0x40
        p >= ProtocolVersions.P_1_21_9 -> 0x3E
        p >= ProtocolVersions.P_1_21_5 -> 0x39
        p >= ProtocolVersions.P_1_21_2 -> 0x3A
        p >= ProtocolVersions.P_1_20_5 -> 0x38
        else -> 0x36
    }

    private fun positionAndLookId(p: Int): Int = when {
        p >= ProtocolVersions.P_26_3 -> 0x49
        p >= ProtocolVersions.P_26_1 -> 0x48
        p >= ProtocolVersions.P_1_21_9 -> 0x46
        p >= ProtocolVersions.P_1_21_5 -> 0x41
        p >= ProtocolVersions.P_1_21_2 -> 0x42
        p >= ProtocolVersions.P_1_20_5 -> 0x40
        else -> 0x3E
    }

    private fun spawnPositionId(p: Int): Int = when {
        p >= ProtocolVersions.P_26_3 -> 0x63
        p >= ProtocolVersions.P_26_1 -> 0x61
        p >= ProtocolVersions.P_1_21_9 -> 0x5F
        p >= ProtocolVersions.P_1_21_5 -> 0x5A
        p >= ProtocolVersions.P_1_21_2 -> 0x5B
        p >= ProtocolVersions.P_1_20_5 -> 0x56
        p >= ProtocolVersions.P_1_20_3 -> 0x54
        else -> 0x52
    }

    private fun gameEventId(p: Int): Int = when {
        p >= ProtocolVersions.P_26_3 -> 0x27
        p >= ProtocolVersions.P_1_21_9 -> 0x26
        p >= ProtocolVersions.P_1_21_5 -> 0x22
        p >= ProtocolVersions.P_1_21_2 -> 0x23
        p >= ProtocolVersions.P_1_20_5 -> 0x22
        else -> 0x20
    }

    private fun chunkId(p: Int): Int = when {
        p >= ProtocolVersions.P_26_3 -> 0x2E
        p >= ProtocolVersions.P_26_1 -> 0x2D
        p >= ProtocolVersions.P_1_21_9 -> 0x2C
        p >= ProtocolVersions.P_1_21_5 -> 0x27
        p >= ProtocolVersions.P_1_21_2 -> 0x28
        p >= ProtocolVersions.P_1_20_5 -> 0x27
        else -> 0x25
    }

    // ---------------------------------------------------------------------
    // 入口：生成一条已写入 packet id 的原始包
    // ---------------------------------------------------------------------

    /** 生成 JoinGame（登录）包。 */
    fun joinGame(version: ProtocolVersion, entityId: Int): ByteBuf {
        val p = version.protocol
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, joinGameId(p))
        buf.writeInt(entityId)
        buf.writeBoolean(false) // hardcore
        ProtocolUtils.writeStringArray(buf, arrayOf("minecraft:overworld"))
        ProtocolUtils.writeVarInt(buf, 100) // maxPlayers
        ProtocolUtils.writeVarInt(buf, 2) // viewDistance
        ProtocolUtils.writeVarInt(buf, 2) // simulationDistance
        buf.writeBoolean(true) // reducedDebugInfo
        buf.writeBoolean(true) // showRespawnScreen
        buf.writeBoolean(false) // doLimitedCrafting
        if (p >= ProtocolVersions.P_1_20_5) {
            ProtocolUtils.writeVarInt(buf, 0) // dimension id (overworld)
        } else {
            ProtocolUtils.writeString(buf, "minecraft:overworld")
        }
        ProtocolUtils.writeString(buf, "minecraft:overworld") // level name
        buf.writeLong(0L) // seed
        if (p >= ProtocolVersions.P_26_3) {
            ProtocolUtils.writeVarInt(buf, GAMEMODE_ADVENTURE)
            ProtocolUtils.writeVarInt(buf, -1) // previous gamemode
        } else {
            buf.writeByte(GAMEMODE_ADVENTURE)
            buf.writeByte(-1) // previous gamemode
        }
        buf.writeBoolean(false) // debug
        buf.writeBoolean(false) // flat
        buf.writeBoolean(false) // last death position present
        ProtocolUtils.writeVarInt(buf, 0) // portal cooldown
        if (p >= ProtocolVersions.P_1_21_2) {
            ProtocolUtils.writeVarInt(buf, 63) // sea level
        }
        if (p >= ProtocolVersions.P_26_2) {
            buf.writeBoolean(false) // online mode
        }
        if (p >= ProtocolVersions.P_1_20_5) {
            buf.writeBoolean(false) // enforces secure chat
        }
        return buf
    }

    /** 生成 PlayerAbilities 包（悬停，禁止飞行速度）。 */
    fun playerAbilities(version: ProtocolVersion): ByteBuf {
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, abilitiesId(version.protocol))
        buf.writeByte(0x02) // flying（悬停，不给 canFly 以免玩家自行切回下落）
        buf.writeFloat(0.0f) // flying speed
        buf.writeFloat(0.1f) // field of view modifier
        return buf
    }

    /** 生成 PlayerPositionAndLook 包（虚空出生点）。 */
    fun playerPositionAndLook(version: ProtocolVersion, teleportId: Int): ByteBuf {
        val p = version.protocol
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, positionAndLookId(p))
        if (p >= ProtocolVersions.P_1_21_2) {
            ProtocolUtils.writeVarInt(buf, teleportId)
            buf.writeDouble(0.0) // x
            buf.writeDouble(400.0) // y
            buf.writeDouble(0.0) // z
            buf.writeDouble(0.0) // velocity x
            buf.writeDouble(0.0) // velocity y
            buf.writeDouble(0.0) // velocity z
            buf.writeFloat(0.0f) // yaw
            buf.writeFloat(0.0f) // pitch
            buf.writeInt(0x08) // flags (relative x/y/z + rotation)
        } else {
            buf.writeDouble(0.0) // x
            buf.writeDouble(400.0) // y
            buf.writeDouble(0.0) // z
            buf.writeFloat(0.0f) // yaw
            buf.writeFloat(0.0f) // pitch
            buf.writeByte(0x08) // flags
            ProtocolUtils.writeVarInt(buf, teleportId)
        }
        return buf
    }

    /** 生成 SpawnPosition 包。 */
    fun spawnPosition(version: ProtocolVersion): ByteBuf {
        val p = version.protocol
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, spawnPositionId(p))
        if (p >= ProtocolVersions.P_1_21_9) {
            ProtocolUtils.writeString(buf, "minecraft:overworld")
        }
        buf.writeLong(encodeBlockPosition(0, 400, 0))
        buf.writeFloat(0.0f) // yaw
        if (p >= ProtocolVersions.P_1_21_9) {
            buf.writeFloat(0.0f) // pitch
        }
        return buf
    }

    /** 生成 GameEvent 包（type=13 start_waiting_for_chunks）。 */
    fun startWaitingForChunks(version: ProtocolVersion): ByteBuf {
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, gameEventId(version.protocol))
        buf.writeByte(13)
        buf.writeFloat(0.0f)
        return buf
    }

    /** 生成一个完全为空的虚空 chunk（含光照）。 */
    fun emptyChunk(version: ProtocolVersion, x: Int, z: Int): ByteBuf {
        val p = version.protocol
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, chunkId(p))
        buf.writeInt(x)
        buf.writeInt(z)
        writeHeightmaps(buf, version)
        writeBlocksData(buf, p)
        ProtocolUtils.writeVarInt(buf, 0) // no block entities
        writeLightData(buf, p)
        return buf
    }

    // ---------------------------------------------------------------------
    // 内部编码
    // ---------------------------------------------------------------------

    private fun writeHeightmaps(buf: ByteBuf, version: ProtocolVersion) {
        val p = version.protocol
        if (p >= ProtocolVersions.P_1_21_5) {
            ProtocolUtils.writeVarInt(buf, 1) // one heightmap
            ProtocolUtils.writeVarInt(buf, 4) // MOTION_BLOCKING ordinal
            ProtocolUtils.writeVarInt(buf, 37) // long count
            repeat(37) { buf.writeLong(0L) }
        } else {
            val motionBlocking = LongArrayBinaryTag.longArrayBinaryTag(*LongArray(37))
            val root = CompoundBinaryTag.builder()
                .put(
                    "root",
                    CompoundBinaryTag.builder().put("MOTION_BLOCKING", motionBlocking).build(),
                )
                .build()
            ProtocolUtils.writeBinaryTag(buf, version, root)
        }
    }

    private fun writeBlocksData(buf: ByteBuf, p: Int) {
        val section = Unpooled.buffer()
        try {
            writeEmptySection(section, p)
            ProtocolUtils.writeVarInt(buf, section.readableBytes() * SECTIONS)
            repeat(SECTIONS) { buf.writeBytes(section, 0, section.readableBytes()) }
        } finally {
            section.release()
        }
    }

    private fun writeEmptySection(buf: ByteBuf, p: Int) {
        buf.writeShort(0) // non-air block count
        if (p >= ProtocolVersions.P_26_1) {
            buf.writeShort(0) // fluid count
        }
        writeSinglePalette(buf, p) // blocks (air)
        writeSinglePalette(buf, p) // biomes (plains)
    }

    private fun writeSinglePalette(buf: ByteBuf, p: Int) {
        buf.writeByte(0) // bits per entry (single palette)
        ProtocolUtils.writeVarInt(buf, 0) // single value
        if (p < ProtocolVersions.P_1_21_5) {
            ProtocolUtils.writeVarInt(buf, 0) // data length
        }
    }

    private fun writeLightData(buf: ByteBuf, p: Int) {
        // sky mask / block mask / empty sky mask —— 均为空
        ProtocolUtils.writeVarInt(buf, 0)
        ProtocolUtils.writeVarInt(buf, 0)
        ProtocolUtils.writeVarInt(buf, 0)
        // empty block mask —— 26 个 bit 全置位
        if (p >= ProtocolVersions.P_26_3) {
            ProtocolUtils.writeVarInt(buf, 4)
            buf.writeByte(0xFF)
            buf.writeByte(0xFF)
            buf.writeByte(0xFF)
            buf.writeByte(0x03)
        } else {
            ProtocolUtils.writeVarInt(buf, 1)
            buf.writeLong(0x3FFFFFFL)
        }
        // sky / block light updates
        ProtocolUtils.writeVarInt(buf, 0)
        ProtocolUtils.writeVarInt(buf, 0)
    }

    private fun encodeBlockPosition(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
}
