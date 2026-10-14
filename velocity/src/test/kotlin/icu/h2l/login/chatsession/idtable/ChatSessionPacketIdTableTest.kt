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

package icu.h2l.login.chatsession.idtable

import com.velocitypowered.api.network.ProtocolVersion
import icu.h2l.login.chatsession.ChatSessionUpdatePacketMatchers
import icu.h2l.login.chatsession.packet.ChatSessionPacketView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ChatSessionPacketIdTable] 的运行时行为测试。
 *
 * 全程不绑定数据目录，因此学习只落在内存、不会写盘；每个用例前后重置，
 * 避免用例之间互相污染。
 */
class ChatSessionPacketIdTableTest {

    /** 已知表中的最大起始协议号是 26.1(775)，26.2(776) 因此是「未来版本」。 */
    private val futureVersion: ProtocolVersion = ProtocolVersion.MINECRAFT_26_2

    @BeforeEach
    fun setUp() {
        ChatSessionPacketIdTable.resetForTest()
    }

    @AfterEach
    fun tearDown() {
        ChatSessionPacketIdTable.resetForTest()
    }

    // ── 内置默认表 ──────────────────────────────────────────────────────────

    @Test
    fun `resolves builtin defaults before any config is loaded`() {
        assertEquals(0x20, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_19_3))
        assertEquals(0x06, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_20_3))
        assertEquals(0x07, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_20_5))
        assertEquals(0x08, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_21_2))
        assertEquals(0x09, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_21_6))
        assertEquals(0x0A, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_26_1))
    }

    @Test
    fun `resolves null and pre-feature versions to null`() {
        assertNull(ChatSessionPacketIdTable.resolve(null))
        assertNull(ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_19_1))
    }

    @Test
    fun `newer-than-table versions inherit the last entry`() {
        assertEquals(0x0A, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    // ── 自学习 ──────────────────────────────────────────────────────────────

    @Test
    fun `learns the id for a version newer than every known entry`() {
        val learned = ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x0B)

        assertTrue(learned, "未来版本应当被学习")
        assertEquals(0x0B, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    @Test
    fun `learning a future version does not disturb known versions`() {
        ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x0B)

        assertEquals(0x20, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_19_3))
        assertEquals(0x06, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_20_3))
        assertEquals(0x07, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_20_5))
        assertEquals(0x0A, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_26_1))
    }

    @Test
    fun `refuses to learn for a version inside the known range`() {
        // 已知版本上出现「结构命中而 id 落空」属于结构误判，绝不能改表。
        val learned = ChatSessionPacketIdTable.observeUnresolvedId(ProtocolVersion.MINECRAFT_1_21, 0x0B)

        assertFalse(learned, "已知版本不应被学习")
        assertEquals(0x07, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_21))
    }

    @Test
    fun `refuses implausible packet ids`() {
        assertFalse(ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x1_0000))
        assertEquals(0x0A, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    @Test
    fun `refuses unknown or pre-feature versions`() {
        assertFalse(ChatSessionPacketIdTable.observeUnresolvedId(null, 0x0B))
        assertFalse(ChatSessionPacketIdTable.observeUnresolvedId(ProtocolVersion.MINECRAFT_1_16_4, 0x0B))
    }

    @Test
    fun `keeps the first learned id when a conflicting observation arrives`() {
        assertTrue(ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x0B))
        assertFalse(ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x0C))

        assertEquals(0x0B, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    @Test
    fun `repeated identical observations are idempotent`() {
        assertTrue(ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x0B))
        assertFalse(ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x0B))

        assertEquals(0x0B, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    @Test
    fun `learning extends the table so later versions inherit the learned id`() {
        assertTrue(ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x0B))
        assertNotNull(ChatSessionPacketIdTable.resolve(futureVersion))
        assertEquals(0x0B, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    // ── 组合策略与自学习的联动 ──────────────────────────────────────────────

    @Test
    fun `composite records an observation when only the structure channel matches`() {
        // 构造一个「结构合法但 id 不在表中」的包：模拟未来版本改了 id 但结构未变。
        val buf = io.netty.buffer.Unpooled.buffer().apply {
            writeVarIntCompat(this, 0x7F)                       // 未登记的 id
            writeLong(java.util.UUID.randomUUID().mostSignificantBits)
            writeLong(java.util.UUID.randomUUID().leastSignificantBits)
            writeLong(System.currentTimeMillis() + 86_400_000L)  // 合理的时间戳
            writeVarIntCompat(this, 294)
            writeBytes(ByteArray(294) { 0x11 })
            writeVarIntCompat(this, 256)
            writeBytes(ByteArray(256) { 0x22 })
        }

        val view = requireNotNull(ChatSessionPacketView.of(buf, futureVersion))

        assertTrue(ChatSessionUpdatePacketMatchers.matches(view), "结构通道应命中")
        // 结构命中而 id 落空 → 已学习
        assertEquals(0x7F, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    @Test
    fun `composite does not learn when both channels agree`() {
        val buf = io.netty.buffer.Unpooled.buffer().apply {
            writeVarIntCompat(this, 0x07)
            writeLong(java.util.UUID.randomUUID().mostSignificantBits)
            writeLong(java.util.UUID.randomUUID().leastSignificantBits)
            writeLong(System.currentTimeMillis() + 86_400_000L)
            writeVarIntCompat(this, 294)
            writeBytes(ByteArray(294) { 0x11 })
            writeVarIntCompat(this, 256)
            writeBytes(ByteArray(256) { 0x22 })
        }

        val view = requireNotNull(
            ChatSessionPacketView.of(buf, ProtocolVersion.MINECRAFT_1_20_5)
        )

        assertTrue(ChatSessionUpdatePacketMatchers.matches(view))
        // 已知版本，不应产生任何学习
        assertEquals(0x07, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_20_5))
        assertEquals(0x0A, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    // ── 落盘、播种与手工编辑 ────────────────────────────────────────────────

    @TempDir
    lateinit var tempDir: Path

    private val configFileName = "chat-session-packet-ids.conf"

    @Test
    fun `seeds the config file on first load using builtin entries`() {
        ChatSessionPacketIdTable.load(tempDir)

        assertTrue(
            Files.exists(tempDir.resolve(configFileName)),
            "首次加载应当生成配置文件",
        )
        // 播种后解析结果应与内置默认表一致
        assertEquals(0x20, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_19_3))
        assertEquals(0x06, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_20_3))
        assertEquals(0x0A, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_26_1))
    }

    @Test
    fun `persists a learned id and reloads it from disk`() {
        ChatSessionPacketIdTable.load(tempDir)
        assertTrue(ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x0B))
        ChatSessionPacketIdTable.flushNow()

        // 清空内存后重新从磁盘加载：学习结果应当仍在
        ChatSessionPacketIdTable.resetForTest()
        ChatSessionPacketIdTable.load(tempDir)

        assertEquals(0x0B, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    @Test
    fun `honours a hand-edited entry from disk`() {
        // 手工写入一份只覆盖 1.19.3 的配置，验证「配置文件优先于内置默认」。
        // 键名必须是 configurate 实际使用的 kebab-case（与生成的 core.conf 一致）。
        Files.writeString(
            tempDir.resolve(configFileName),
            """
            auto-learn=true
            packet-ids=[
                { from-protocol=761, packet-id=33 }
            ]
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        ChatSessionPacketIdTable.load(tempDir)

        // 覆盖生效：761 现在是 33 而非内置默认的 0x20
        assertEquals(33, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_19_3))
        // 未被覆盖的内置条目仍然可用
        assertEquals(0x06, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_1_20_3))
        assertEquals(0x0A, ChatSessionPacketIdTable.resolve(ProtocolVersion.MINECRAFT_26_1))
    }

    @Test
    fun `auto-learn disabled writes nothing new to disk`() {
        Files.writeString(
            tempDir.resolve(configFileName),
            """
            auto-learn=false
            packet-ids=[
                { from-protocol=761, packet-id=32 }
            ]
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        ChatSessionPacketIdTable.load(tempDir)

        assertFalse(ChatSessionPacketIdTable.observeUnresolvedId(futureVersion, 0x0B))
        ChatSessionPacketIdTable.flushNow()

        ChatSessionPacketIdTable.resetForTest()
        ChatSessionPacketIdTable.load(tempDir)
        assertEquals(0x0A, ChatSessionPacketIdTable.resolve(futureVersion))
    }

    private fun writeVarIntCompat(out: io.netty.buffer.ByteBuf, value: Int) {
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
}
