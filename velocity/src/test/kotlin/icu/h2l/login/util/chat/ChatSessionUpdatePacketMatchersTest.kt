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

package icu.h2l.login.util.chat

import com.velocitypowered.api.network.ProtocolVersion
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ChatSessionUpdatePacketMatchersTest {

    // ── 构造辅助 ────────────────────────────────────────────────────────────

    /** 写一个 Minecraft VarInt。 */
    private fun writeVarInt(out: io.netty.buffer.ByteBuf, value: Int) {
        var remaining = value
        while (true) {
            if (remaining and 0x7F.inv() == 0) {
                out.writeByte(remaining)
                return
            }
            out.writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }

    /**
     * 构造一个 `CHAT_SESSION_UPDATE` 数据包的完整字节流（含包头 id）。
     */
    private fun chatSessionPacket(
        packetId: Int,
        sessionId: UUID = UUID.randomUUID(),
        expiry: Long = System.currentTimeMillis() + 86_400_000L,
        keyLength: Int = 294,
        signatureLength: Int = 256,
    ) = Unpooled.buffer().apply {
        writeVarInt(this, packetId)
        writeLong(sessionId.mostSignificantBits)
        writeLong(sessionId.leastSignificantBits)
        writeLong(expiry)
        writeVarInt(this, keyLength)
        writeBytes(ByteArray(keyLength) { 0x11 })
        writeVarInt(this, signatureLength)
        writeBytes(ByteArray(signatureLength) { 0x22 })
    }

    private fun viewOf(buf: io.netty.buffer.ByteBuf, version: ProtocolVersion? = ProtocolVersion.MINECRAFT_1_20_5) =
        requireNotNull(ChatSessionPacketView.of(buf, version))

    // ── id 通道 ─────────────────────────────────────────────────────────────

    @Test
    fun `packet id matcher hits the id registered for the version`() {
        val view = viewOf(chatSessionPacket(packetId = 0x07))
        assertTrue(PacketIdChatSessionUpdateMatcher.matches(view))
    }

    @Test
    fun `packet id matcher ignores an id not registered for the version`() {
        // 1.20.5 登记的 id 是 0x07，换成别的 id 就不该命中。
        val view = viewOf(chatSessionPacket(packetId = 0x22))
        assertFalse(PacketIdChatSessionUpdateMatcher.matches(view))

        // 同一个字节流在 1.19.3 下也不该命中：该版本登记的是 0x20。
        val view193 = viewOf(chatSessionPacket(packetId = 0x07), ProtocolVersion.MINECRAFT_1_19_3)
        assertFalse(PacketIdChatSessionUpdateMatcher.matches(view193))
    }

    @Test
    fun `packet id resolver covers the documented version ranges`() {
        assertEquals(0x20, ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_1_19_3))
        assertEquals(0x06, ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_1_19_4))
        assertEquals(0x06, ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_1_20_3))
        assertEquals(0x07, ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_1_20_5))
        assertEquals(0x07, ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_1_21))
        assertEquals(0x08, ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_1_21_2))
        assertEquals(0x08, ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_1_21_5))
    }

    @Test
    fun `packet id resolver returns null for versions below the feature introduction`() {
        assertNull(ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_1_19_1))
        assertNull(ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_1_16_4))
        assertNull(ChatSessionUpdatePacketIdResolver.resolve(null))
    }

    @Test
    fun `packet id resolver keeps matching versions newer than the table`() {
        // 表中最后一个条目起始于 26.1(775)。比它更新的版本必须沿用该条目，
        // 而不是被「最新版本」截断成 null —— 这是对未来版本的兼容保证。
        assertEquals(0x0A, ChatSessionUpdatePacketIdResolver.resolve(ProtocolVersion.MINECRAFT_26_2))
    }

    @Test
    fun `packet id resolver leaves no gap across all supported versions`() {
        ProtocolVersion.SUPPORTED_VERSIONS
            .filter { it.protocol >= 761 }
            .forEach { version ->
                assertNotNull(
                    ChatSessionUpdatePacketIdResolver.resolve(version),
                    "下界查找表在 $version 处出现空隙",
                )
            }
    }

    @Test
    fun `packet id resolver covers multi-name versions such as 1_20_4`() {
        // Velocity 没有 MINECRAFT_1_20_4 常量：1.20.3 与 1.20.4 共用协议号 765，
        // 因此只按枚举常量遍历会漏检「1.20.4」这类名字。这里按版本名逐名断言。
        val oneTwentyThree = ProtocolVersion.MINECRAFT_1_20_3
        assertTrue(
            oneTwentyThree.versionsSupportedBy.contains("1.20.4"),
            "前提校验：1.20.3 常量应当同时代表 1.20.4",
        )
        assertEquals(0x06, ChatSessionUpdatePacketIdResolver.resolve(oneTwentyThree))
        assertEquals(
            ChatSessionUpdatePacketLayout.UUID_THEN_PROFILE_KEY,
            ChatSessionUpdateLayoutResolver.resolve(oneTwentyThree),
        )

        // 逐名覆盖：每个受支持版本名都必须能解析出结果。
        ProtocolVersion.SUPPORTED_VERSIONS
            .filter { it.protocol >= 761 }
            .forEach { version ->
                version.versionsSupportedBy.forEach { name ->
                    assertNotNull(
                        ChatSessionUpdatePacketIdResolver.resolve(version),
                        "版本名 $name 未覆盖",
                    )
                }
            }
    }

    @Test
    fun `layout resolver keeps matching versions newer than the table`() {
        // 结构解析同样不设上界：未来版本沿用已知结构，而不是判定为不可判定。
        assertEquals(
            ChatSessionUpdatePacketLayout.UUID_THEN_PROFILE_KEY,
            ChatSessionUpdateLayoutResolver.resolve(ProtocolVersion.MINECRAFT_26_2),
        )
        assertNotNull(ChatSessionUpdateLayoutResolver.resolve(ProtocolVersion.MINECRAFT_1_19_3))
        assertNull(ChatSessionUpdateLayoutResolver.resolve(ProtocolVersion.MINECRAFT_1_19_1))
    }

    // ── 结构通道 ────────────────────────────────────────────────────────────

    @Test
    fun `structure matcher hits a well-formed chat session payload`() {
        val view = viewOf(chatSessionPacket(packetId = 0x07))
        assertTrue(StructureChatSessionUpdateMatcher.matches(view))
    }

    @Test
    fun `structure matcher hits even when the packet id is unknown`() {
        // 模拟未来版本改了 id 但结构未变：id 通道落空，结构通道仍应命中。
        val view = viewOf(chatSessionPacket(packetId = 0x7F))
        assertFalse(PacketIdChatSessionUpdateMatcher.matches(view))
        assertTrue(StructureChatSessionUpdateMatcher.matches(view))
        assertTrue(ChatSessionUpdatePacketMatchers.matches(view))
    }

    @Test
    fun `structure matcher rejects a payload with trailing bytes`() {
        val buf = chatSessionPacket(packetId = 0x07).writeByte(0x00)
        assertFalse(StructureChatSessionUpdateMatcher.matches(viewOf(buf)))
    }

    @Test
    fun `structure matcher rejects an implausible expiry timestamp`() {
        val view = viewOf(chatSessionPacket(packetId = 0x07, expiry = 12L))
        assertFalse(StructureChatSessionUpdateMatcher.matches(view))
    }

    @Test
    fun `structure matcher rejects an all-zero session uuid`() {
        val view = viewOf(chatSessionPacket(packetId = 0x07, sessionId = UUID(0L, 0L)))
        assertFalse(StructureChatSessionUpdateMatcher.matches(view))
    }

    @Test
    fun `structure matcher rejects a truncated payload`() {
        val full = chatSessionPacket(packetId = 0x07)
        val truncated = full.copy(0, full.readableBytes() - 40)
        assertFalse(StructureChatSessionUpdateMatcher.matches(viewOf(truncated)))
    }

    @Test
    fun `structure matcher rejects arbitrary unrelated payloads`() {
        // 典型的无关包：一个 keep-alive，只有一个 long。
        val keepAlive = Unpooled.buffer().apply {
            writeVarInt(this, 0x18)
            writeLong(123456789L)
        }
        assertFalse(StructureChatSessionUpdateMatcher.matches(viewOf(keepAlive)))

        // 空包体
        val empty = Unpooled.buffer().apply { writeVarInt(this, 0x00) }
        assertFalse(StructureChatSessionUpdateMatcher.matches(viewOf(empty)))
    }

    // ── 组合策略 ────────────────────────────────────────────────────────────

    @Test
    fun `composite matches when either channel hits`() {
        assertTrue(ChatSessionUpdatePacketMatchers.matches(viewOf(chatSessionPacket(packetId = 0x07))))
        assertTrue(ChatSessionUpdatePacketMatchers.matches(viewOf(chatSessionPacket(packetId = 0x7F))))
    }

    @Test
    fun `composite passes through packets that neither channel claims`() {
        val pluginMessage = Unpooled.buffer().apply {
            writeVarInt(this, 0x12)
            writeBytes(ByteArray(64) { 0x33 })
        }
        assertFalse(ChatSessionUpdatePacketMatchers.matches(viewOf(pluginMessage)))
    }

    @Test
    fun `hit names report which channels fired`() {
        val both = ChatSessionUpdatePacketMatchers.hitNames(viewOf(chatSessionPacket(packetId = 0x07)))
        assertTrue(both.contains("packet-id"))
        assertTrue(both.contains("structure"))

        val structureOnly = ChatSessionUpdatePacketMatchers.hitNames(viewOf(chatSessionPacket(packetId = 0x7F)))
        assertFalse(structureOnly.contains("packet-id"))
        assertTrue(structureOnly.contains("structure"))
    }

    // ── 缓冲区分割 ──────────────────────────────────────────────────────────

    @Test
    fun `view factory splits header id from payload without touching the source buffer`() {
        val buf = chatSessionPacket(packetId = 0x07)
        val before = buf.readerIndex()

        val view = requireNotNull(ChatSessionPacketView.of(buf, ProtocolVersion.MINECRAFT_1_20_5))

        assertEquals(0x07, view.packetId)
        assertEquals(before, buf.readerIndex(), "source buffer must not be consumed")
        // payload 应恰好是包体长度：UUID(16) + expiry(8) + VarInt(294)=2字节 + 294 + VarInt(256)=2字节 + 256
        assertEquals(16 + 8 + 2 + 294 + 2 + 256, view.payloadLength)
    }

    @Test
    fun `view factory returns null for an empty buffer`() {
        assertNull(ChatSessionPacketView.of(Unpooled.buffer(), ProtocolVersion.MINECRAFT_1_20_5))
    }
}
