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

package icu.h2l.login.chatsession.packet

import com.velocitypowered.api.network.ProtocolVersion

/**
 * 按协议版本解析 `CHAT_SESSION_UPDATE` 的包体结构。
 *
 * 与 [ChatSessionUpdatePacketIdResolver] 采用相同的**下界查找**策略：
 * 只记录「从某个协议号开始，结构变成 X」，取最后一个起始协议号不大于当前版本的表项。
 * 因此**不存在「最新版本截断」**：比表中最后一条更新的版本会沿用最后一条，
 * 不会被判定为「结构不可判定」。表项之间也不可能出现空隙。
 *
 * 目前 1.19.3 ~ 26.x 全部共用 [ChatSessionUpdatePacketLayout.UUID_THEN_PROFILE_KEY]。
 * 未来结构真变了，追加一条 `LayoutEntry` 即可，匹配层无需改动。
 */
object ChatSessionUpdateLayoutResolver {

    /**
     * 原始协议号，不依赖 Velocity 枚举常量是否存在。
     *
     * ⚠️ 注意：标的是**协议号**，不是版本号。Minecraft 存在大量共用同一协议号的版本
     * （例如 1.20.3 与 1.20.4 同为 765），Velocity 只提供其中一个枚举常量。
     * 因此「表里没有 1.20.4」是共用协议号的正常结果，不是漏配。
     */
    private const val PROTOCOL_1_19_3: Int = 761  // 1.19.3

    /** `CHAT_SESSION_UPDATE` 首次出现的协议号（Minecraft 1.19.3）。 */
    private const val MIN_SUPPORTED_PROTOCOL: Int = PROTOCOL_1_19_3

    /**
     * 一条「起始协议号 → 结构」的映射。
     *
     * [fromProtocol] 为闭区间下界；上界由**下一条表项**决定，最后一条无上界。
     */
    private data class LayoutEntry(
        val layout: ChatSessionUpdatePacketLayout,
        val fromProtocol: Int,
    )

    /** 按 [LayoutEntry.fromProtocol] 升序排列。 */
    private val LAYOUT_TABLE: List<LayoutEntry> = listOf(
        // 1.19.3 起沿用至今，且对未来版本持续生效（无上界）。
        LayoutEntry(
            layout = ChatSessionUpdatePacketLayout.UUID_THEN_PROFILE_KEY,
            fromProtocol = PROTOCOL_1_19_3,
        ),
    )

    /**
     * 解析 [protocolVersion] 对应的包体结构。
     *
     * @return 命中时为对应结构；版本未知（null / UNKNOWN / LEGACY）或早于
     *         `CHAT_SESSION_UPDATE` 引入版本时为 null。
     */
    fun resolve(protocolVersion: ProtocolVersion?): ChatSessionUpdatePacketLayout? {
        val protocol = protocolVersion?.protocol ?: return null
        if (protocol < MIN_SUPPORTED_PROTOCOL) {
            return null
        }

        return LAYOUT_TABLE.lastOrNull { protocol >= it.fromProtocol }?.layout
    }

    /**
     * 兜底结构：版本无法判定时使用。
     *
     * 所有已知版本结构一致，且结构匹配器本身足够严格（要求长度自洽、时间戳合理、
     * 恰好填满缓冲区），因此在版本未知时按通用结构尝试解析是净收益。
     */
    val fallbackLayout: ChatSessionUpdatePacketLayout
        get() = ChatSessionUpdatePacketLayout.UUID_THEN_PROFILE_KEY
}
