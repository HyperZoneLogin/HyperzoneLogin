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

/**
 * 按协议版本解析 `CHAT_SESSION_UPDATE`（serverbound）的数据包 id。
 *
 * ── 查表方式：下界查找（floor lookup），不设上界 ──────────────────────────
 * 表里只记录「从某个协议号开始，id 变成 X」。解析时取**最后一个**
 * 起始协议号不大于当前版本的表项，因此：
 *
 * - 比最后一个表项更新的版本（未来的 1.22 / 27.x …）会**沿用最后一个表项**，
 *   不会被「最新版本」截断 —— 这正是兼容性最好的默认行为；
 * - 表项之间**不可能出现空隙**：任何版本号都必然落在某个起始点之后。
 *
 * 若未来某个版本改了 id，只需追加一条 `IdEntry`；旧版本行为不受影响。
 *
 * ── 为什么用原始协议号而不是枚举常量 ─────────────────────────────────────
 * [ProtocolVersion.getProtocol] 对任意枚举常量都可用，不依赖 `MINECRAFT_1_21_6`
 * 这类较新常量是否存在于旧版 Velocity；同时也避开了枚举常量缺失时的回退分支。
 *
 * 参考：ViaVersion `ServerboundPackets1_19_3` / `1_19_4` / `1_20_3` / `1_20_5` /
 * `1_21_2` / `1_21_4` / `1_21_5` / `1_21_6` / `26_1` / `26_3`。
 */
object ChatSessionUpdatePacketIdResolver {

    // ── 原始协议号（不依赖 Velocity 枚举常量是否存在） ──
    //
    // ⚠️ 注意：这里标的是**协议号**，不是版本号。Minecraft 有大量版本共用同一个协议号，
    // Velocity 因此只提供其中一个枚举常量。下表括号内为「该协议号实际覆盖的全部版本名」，
    // 那些没有独立常量的版本（例如 1.20.4）由共用协议号自然覆盖，**不需要**也无法在 ID_TABLE
    // 里单列一条 —— 若日后发现「某版本没被匹配」，先核对本注释再判断是不是真的缺陷。
    private const val PROTOCOL_1_19_3: Int = 761  // 1.19.3
    private const val PROTOCOL_1_19_4: Int = 762  // 1.19.4
    private const val PROTOCOL_1_20_5: Int = 766  // 1.20.5、1.20.6
    private const val PROTOCOL_1_21_2: Int = 768  // 1.21.2、1.21.3
    private const val PROTOCOL_1_21_6: Int = 771  // 1.21.6
    private const val PROTOCOL_26_1: Int = 775    // 26.1、26.1.1、26.1.2

    /** `CHAT_SESSION_UPDATE` 首次出现的协议号（Minecraft 1.19.3）。 */
    private const val MIN_SUPPORTED_PROTOCOL: Int = PROTOCOL_1_19_3

    /**
     * 内置默认表，按起始协议号升序。
     *
     * 注释里的区间写的是**版本名**，但匹配用的是协议号。区间内存在共用协议号的多版本
     * （如 1.20.3 / 1.20.4 同为 765），它们**不会**、也**无法**各自单列一条。
     * 因此「表里没有 1.20.4」是正常现象，不是漏配。
     *
     * 这张表同时作为 [ChatSessionPacketIdTable] 的兜底与首次播种来源。
     */
    private val ID_TABLE: List<ChatSessionPacketIdConfig.PacketIdEntry> = listOf(
        // 1.19.3（协议号 761）
        ChatSessionPacketIdConfig.PacketIdEntry(packetId = 0x20, fromProtocol = PROTOCOL_1_19_3),
        // 1.19.4 ~ 1.20.3（协议号 762~765；注意 765 同时代表 1.20.3 与 1.20.4）
        ChatSessionPacketIdConfig.PacketIdEntry(packetId = 0x06, fromProtocol = PROTOCOL_1_19_4),
        // 1.20.5 ~ 1.21.1（协议号 766~767；766 同时代表 1.20.5/1.20.6，767 代表 1.21/1.21.1）
        ChatSessionPacketIdConfig.PacketIdEntry(packetId = 0x07, fromProtocol = PROTOCOL_1_20_5),
        // 1.21.2 ~ 1.21.5（协议号 768~770；768 同时代表 1.21.2/1.21.3）
        ChatSessionPacketIdConfig.PacketIdEntry(packetId = 0x08, fromProtocol = PROTOCOL_1_21_2),
        // 1.21.6 ~ 1.21.11（协议号 771~774；含 1.21.7 / 1.21.9）
        ChatSessionPacketIdConfig.PacketIdEntry(packetId = 0x09, fromProtocol = PROTOCOL_1_21_6),
        // 26.1 及以上（协议号 775+）：无上界，未来版本沿用 0x0A
        ChatSessionPacketIdConfig.PacketIdEntry(packetId = 0x0A, fromProtocol = PROTOCOL_26_1),
    )

    /** 内置默认表快照，供 [ChatSessionPacketIdTable] 播种与兜底。 */
    fun builtinEntries(): List<ChatSessionPacketIdConfig.PacketIdEntry> = ID_TABLE

    /**
     * 解析 [protocolVersion] 对应的数据包 id。
     *
     * @return 命中时为对应 id；版本未知（null / UNKNOWN / LEGACY）或早于
     *         `CHAT_SESSION_UPDATE` 引入版本时为 null。
     */
    fun resolve(protocolVersion: ProtocolVersion?): Int? {
        val protocol = protocolVersion?.protocol ?: return null
        if (protocol < MIN_SUPPORTED_PROTOCOL) {
            return null
        }

        return ID_TABLE.lastOrNull { protocol >= it.fromProtocol }?.packetId
    }
}
