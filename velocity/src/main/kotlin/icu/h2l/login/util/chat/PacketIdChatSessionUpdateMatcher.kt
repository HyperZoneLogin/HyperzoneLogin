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
 * 基于**数据包 id** 的匹配器（快速通道）。
 *
 * 直接用协议版本查表得到 `CHAT_SESSION_UPDATE` 的 id 并与包头 id 比较，代价极低。
 *
 * 局限：id 表依赖版本，新协议版本发布后若未及时登记就会漏判；
 * 因此它必须与 [StructureChatSessionUpdateMatcher] 组合使用。
 */
object PacketIdChatSessionUpdateMatcher : ChatSessionUpdatePacketMatcher {

    override val name: String = "packet-id"

    override fun matches(view: ChatSessionPacketView): Boolean {
        val expected = ChatSessionUpdatePacketIdResolver.resolve(view.protocolVersion) ?: return false
        return view.packetId == expected
    }
}
