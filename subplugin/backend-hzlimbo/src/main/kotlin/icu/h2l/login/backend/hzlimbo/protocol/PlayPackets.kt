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
import icu.h2l.login.backend.hzlimbo.config.HzLimboConfig
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

    private const val MAX_PLAYERS = 100
    private const val VIEW_DISTANCE = 2
    private const val SIMULATION_DISTANCE = 2
    private const val GAME_MODE_ADVENTURE = 2

    /** overworld 最低 Y。 */
    private const val MIN_Y = -64

    /** 空气 / 石头的全局 block state id（1.20.2+ 稳定）。 */
    private const val AIR_STATE_ID = 0
    private const val STONE_STATE_ID = 1

    /** 光照 section 数（24 个方块 section + 上下各 1）。 */
    private const val LIGHT_SECTIONS = 26
    private const val LIGHT_MASK = 0x3FFFFFFL

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

    /** `initialize_border` 的包 id（各版本不同；未知版本返回 null，不发送边界）。 */
    private fun borderId(p: Int): Int? = when {
        p >= ProtocolVersions.P_26_3 -> 0x2C
        p == ProtocolVersions.P_26_2 -> null // 26.2 未验证，避免发包错位
        p >= ProtocolVersions.P_26_1 -> 0x2B
        p >= ProtocolVersions.P_1_21_9 -> 0x2A
        p >= ProtocolVersions.P_1_21_5 -> 0x25
        p >= ProtocolVersions.P_1_21_2 -> 0x26
        p >= ProtocolVersions.P_1_20_5 -> 0x25
        p >= ProtocolVersions.P_1_20_2 -> 0x23
        else -> null
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
        ProtocolUtils.writeVarInt(buf, MAX_PLAYERS)
        ProtocolUtils.writeVarInt(buf, VIEW_DISTANCE)
        ProtocolUtils.writeVarInt(buf, SIMULATION_DISTANCE)
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
            ProtocolUtils.writeVarInt(buf, GAME_MODE_ADVENTURE)
            ProtocolUtils.writeVarInt(buf, -1) // previous gamemode
        } else {
            buf.writeByte(GAME_MODE_ADVENTURE)
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

    /** 生成 PlayerAbilities 包。 */
    fun playerAbilities(version: ProtocolVersion, allowMovement: Boolean): ByteBuf {
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, abilitiesId(version.protocol))
        if (allowMovement) {
            buf.writeByte(0x00) // 正常行走（重力生效）
            buf.writeFloat(0.05f) // flying speed
        } else {
            buf.writeByte(0x02) // flying（原地悬停）
            buf.writeFloat(0.0f) // flying speed
        }
        buf.writeFloat(0.1f) // walking speed / FOV modifier
        return buf
    }

    /** 生成 PlayerPositionAndLook 包。 */
    fun playerPositionAndLook(
        version: ProtocolVersion,
        teleportId: Int,
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
    ): ByteBuf {
        val p = version.protocol
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, positionAndLookId(p))
        if (p >= ProtocolVersions.P_1_21_2) {
            ProtocolUtils.writeVarInt(buf, teleportId)
            buf.writeDouble(x)
            buf.writeDouble(y)
            buf.writeDouble(z)
            buf.writeDouble(0.0) // velocity x
            buf.writeDouble(0.0) // velocity y
            buf.writeDouble(0.0) // velocity z
            buf.writeFloat(yaw)
            buf.writeFloat(pitch)
            buf.writeInt(0x08) // flags (relative x/y/z + rotation)
        } else {
            buf.writeDouble(x)
            buf.writeDouble(y)
            buf.writeDouble(z)
            buf.writeFloat(yaw)
            buf.writeFloat(pitch)
            buf.writeByte(0x08) // flags
            ProtocolUtils.writeVarInt(buf, teleportId)
        }
        return buf
    }

    /** 生成 SpawnPosition 包。 */
    fun spawnPosition(
        version: ProtocolVersion,
        x: Int,
        y: Int,
        z: Int,
        yaw: Float,
        pitch: Float,
    ): ByteBuf {
        val p = version.protocol
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, spawnPositionId(p))
        if (p >= ProtocolVersions.P_1_21_9) {
            ProtocolUtils.writeString(buf, "minecraft:overworld")
        }
        buf.writeLong(encodeBlockPosition(x, y, z))
        buf.writeFloat(yaw)
        if (p >= ProtocolVersions.P_1_21_9) {
            buf.writeFloat(pitch)
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

    /** 生成一个带内置石台的 chunk（含高度图与光照）。 */
    fun chunk(
        version: ProtocolVersion,
        x: Int,
        z: Int,
        world: HzLimboConfig.WorldConfig,
    ): ByteBuf {
        val p = version.protocol
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, chunkId(p))
        buf.writeInt(x)
        buf.writeInt(z)
        writeHeightmaps(buf, version, world)
        writeBlocksData(buf, p, world)
        ProtocolUtils.writeVarInt(buf, 0) // no block entities
        writeLightData(buf, p)
        return buf
    }

    /** 生成 Initialize World Border 包（未知版本返回 null）。 */
    fun worldBorder(
        version: ProtocolVersion,
        centerX: Double,
        centerZ: Double,
        size: Double,
    ): ByteBuf? {
        val id = borderId(version.protocol) ?: return null
        val buf = Unpooled.buffer()
        ProtocolUtils.writeVarInt(buf, id)
        buf.writeDouble(centerX)
        buf.writeDouble(centerZ)
        buf.writeDouble(size) // old size
        buf.writeDouble(size) // new size
        buf.writeByte(0) // lerp time (varlong 0)
        ProtocolUtils.writeVarInt(buf, size.toInt().coerceAtLeast(1)) // absolute max size
        ProtocolUtils.writeVarInt(buf, 0) // warning blocks
        ProtocolUtils.writeVarInt(buf, 0) // warning time
        return buf
    }

    // ---------------------------------------------------------------------
    // 内部编码
    // ---------------------------------------------------------------------

    private fun writeHeightmaps(buf: ByteBuf, version: ProtocolVersion, world: HzLimboConfig.WorldConfig) {
        val p = version.protocol
        val platform = world.platform
        // 高度图的值 = 方块 Y - minY + 1；没有石台时保持 0（空）。
        val height = if (platform.enabled) platform.y - MIN_Y + 1 else 0
        val motionBlocking = packHeightmap(height)

        if (p >= ProtocolVersions.P_1_21_5) {
            ProtocolUtils.writeVarInt(buf, 1) // one heightmap
            ProtocolUtils.writeVarInt(buf, 4) // MOTION_BLOCKING ordinal
            ProtocolUtils.writeVarInt(buf, motionBlocking.size)
            for (value in motionBlocking) {
                buf.writeLong(value)
            }
        } else {
            val surface = LongArrayBinaryTag.longArrayBinaryTag(*motionBlocking)
            val root = CompoundBinaryTag.builder()
                .put("MOTION_BLOCKING", surface)
                .put("WORLD_SURFACE", surface)
                .build()
            ProtocolUtils.writeBinaryTag(buf, version, root)
        }
    }

    /** 9-bit heightmap，256 列，每 long 7 个值（1.16+ 的打包方式）。 */
    private fun packHeightmap(height: Int): LongArray {
        val longs = LongArray(37)
        val value = (height and 0x1FF).toLong()
        for (index in 0 until 256) {
            val cell = index / 7
            val bit = (index % 7) * 9
            longs[cell] = longs[cell] or (value shl bit)
        }
        return longs
    }

    private fun writeBlocksData(buf: ByteBuf, p: Int, world: HzLimboConfig.WorldConfig) {
        val platform = world.platform
        val platformSection = if (platform.enabled) sectionIndex(platform.y) else -1

        val emptySection = Unpooled.buffer()
        val platformSectionBuf = Unpooled.buffer()
        try {
            writeEmptySection(emptySection, p)
            if (platformSection >= 0) {
                writePlatformSection(platformSectionBuf, p, platform.y, platformSection)
            }

            val sectionCount = SECTIONS
            var dataLength = emptySection.readableBytes() * sectionCount
            if (platformSection >= 0) {
                dataLength += platformSectionBuf.readableBytes() - emptySection.readableBytes()
            }
            ProtocolUtils.writeVarInt(buf, dataLength)

            for (section in 0 until SECTIONS) {
                val source = if (section == platformSection) platformSectionBuf else emptySection
                buf.writeBytes(source, 0, source.readableBytes())
            }
        } finally {
            emptySection.release()
            platformSectionBuf.release()
        }
    }

    /** 计算方块 Y 所在的 section 下标（0..23）。 */
    private fun sectionIndex(y: Int): Int = ((y - MIN_Y) / 16).coerceIn(0, SECTIONS - 1)

    private fun writePlatformSection(buf: ByteBuf, p: Int, platformY: Int, section: Int) {
        val localY = platformY - (section * 16 + MIN_Y)
        buf.writeShort(256) // non-air block count (16x16)
        if (p >= ProtocolVersions.P_26_1) {
            buf.writeShort(0) // fluid count
        }

        // blocks: bits=4, local palette [air(0), stone(1)]
        buf.writeByte(4)
        ProtocolUtils.writeVarInt(buf, 2)
        ProtocolUtils.writeVarInt(buf, AIR_STATE_ID)
        ProtocolUtils.writeVarInt(buf, STONE_STATE_ID)

        val longs = LongArray(256) // 4096 values * 4 bits / 64
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                val index = (localY shl 8) or (z shl 4) or x
                val cell = index ushr 4
                val bit = (index and 15) * 4
                longs[cell] = longs[cell] or (1L shl bit) // 1 = stone
            }
        }
        if (p < ProtocolVersions.P_1_21_5) {
            ProtocolUtils.writeVarInt(buf, longs.size)
        }
        for (value in longs) {
            buf.writeLong(value)
        }

        writeSinglePalette(buf, p) // biomes (plains)
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
        // 天空光：26 个 section 全部给 15
        writeBitSet(buf, p, LIGHT_MASK)
        ProtocolUtils.writeVarInt(buf, 0) // block light mask
        ProtocolUtils.writeVarInt(buf, 0) // empty sky light mask
        writeBitSet(buf, p, LIGHT_MASK) // empty block light mask

        val fullLight = ByteArray(2048) { 0xFF.toByte() } // 4096 nibbles = 15
        ProtocolUtils.writeVarInt(buf, LIGHT_SECTIONS)
        repeat(LIGHT_SECTIONS) {
            ProtocolUtils.writeByteArray(buf, fullLight)
        }
        ProtocolUtils.writeVarInt(buf, 0) // block light arrays
    }

    private fun writeBitSet(buf: ByteBuf, p: Int, mask: Long) {
        if (p >= ProtocolVersions.P_26_3) {
            ProtocolUtils.writeVarInt(buf, 4) // byte count
            buf.writeByte((mask and 0xFF).toInt())
            buf.writeByte(((mask ushr 8) and 0xFF).toInt())
            buf.writeByte(((mask ushr 16) and 0xFF).toInt())
            buf.writeByte(((mask ushr 24) and 0xFF).toInt())
        } else {
            ProtocolUtils.writeVarInt(buf, 1) // long count
            buf.writeLong(mask)
        }
    }

    private fun encodeBlockPosition(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
}
