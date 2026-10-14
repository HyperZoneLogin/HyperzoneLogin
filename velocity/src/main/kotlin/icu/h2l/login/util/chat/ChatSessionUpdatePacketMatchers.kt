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

/**
 * `CHAT_SESSION_UPDATE` 匹配器集合（组合策略）。
 *
 * 同时启用两条互相独立的匹配通道，任一命中即判定为目标包：
 *
 * - [PacketIdChatSessionUpdateMatcher]：查版本 id 表，快但依赖 id 表及时更新；
 * - [StructureChatSessionUpdateMatcher]：解析包体结构，慢但不受 id 变动影响。
 *
 * 两者组合的意义在于**提高命中率**：新版本若改了 id 但结构未变，结构通道仍能命中；
 * 若某版本结构特殊但 id 已登记，id 通道也能命中。只有两者同时落空才放行。
 *
 * ── 放行安全性 ───────────────────────────────────────────────────────────
 * [matches] 为 false 时调用方必须原样转发该包。这不是「未知情况」，而是绝大多数包的
 * 正常路径：代理每秒转发的无关包远多于目标包，两条通道都需给出明确肯定才拦截。
 */
object ChatSessionUpdatePacketMatchers {

    /** 参与匹配的全部策略；顺序即短路顺序，快的放前面。 */
    private val MATCHERS: List<ChatSessionUpdatePacketMatcher> = listOf(
        PacketIdChatSessionUpdateMatcher,
        StructureChatSessionUpdateMatcher,
    )

    /**
     * 返回命中的匹配器名称列表。
     *
     * 会**跑完全部匹配器**而不短路，便于诊断「到底是哪条通道命中」；
     * 若只关心结论请用 [matches]。
     */
    fun hitNames(view: ChatSessionPacketView): List<String> {
        return MATCHERS.filter { matcher ->
            runCatching { matcher.matches(view) }.getOrDefault(false)
        }.map { it.name }
    }

    /** 是否判定为 `CHAT_SESSION_UPDATE` 包。任一匹配器命中即为 true。 */
    fun matches(view: ChatSessionPacketView): Boolean {
        return MATCHERS.any { matcher ->
            runCatching { matcher.matches(view) }.getOrDefault(false)
        }
    }
}
