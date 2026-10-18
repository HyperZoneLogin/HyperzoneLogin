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

package icu.h2l.login.chatsession
import icu.h2l.login.chatsession.packet.ChatSessionPacketView

/**
 * `CHAT_SESSION_UPDATE` 匹配器。
 *
 * 实现必须满足两条硬性约定：
 * 1. **绝不抛出**：待匹配的数据包大多是无关包，解析失败是常态而非异常；
 * 2. **绝不改动** [ChatSessionPacketView.payload] 的 readerIndex，也不 `release` 它。
 *
 * 单个匹配器判 false 只代表「本策略没命中」，不代表该包不是目标包；
 * 是否放行由 [ChatSessionUpdatePacketMatchers] 汇总全部策略后决定。
 */
interface ChatSessionUpdatePacketMatcher {
    /** 匹配器名称，用于 debug 日志诊断。 */
    val name: String

    /**
     * 判断 [view] 是否为 `CHAT_SESSION_UPDATE` 包。
     *
     * @return true 表示命中；任何无法判定或解析失败的情况都应返回 false。
     */
    fun matches(view: ChatSessionPacketView): Boolean
}
