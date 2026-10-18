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

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

/**
 * `CHAT_SESSION_UPDATE` 拦截助手（helper）的落盘配置。
 *
 * 文件位于插件数据目录（`helper-chatsession.conf`），首次运行时会用内置默认表播种，
 * 之后可手工编辑；结构匹配学到的新条目也会自动追写进来（相当于运行时缓存）。
 */
@ConfigSerializable
data class ChatSessionPacketIdConfig(
    // 不给服务器发送 CHAT_SESSION_UPDATE 包
    @Comment("config.chat-session.kill-chat-session")
    val killChatSession: Boolean = true,

    // 本拦截助手的 debug 日志开关（独立于核心的 network-rewrite 日志）
    @Comment("config.chat-session.debug")
    val debug: Boolean = false,

    // 结构匹配命中但 id 匹配落空时，是否自动学习并写入本文件
    @Comment("config.chat-session.auto-learn")
    val autoLearn: Boolean = true,

    // 起始协议号 → 数据包 id
    @Comment("config.chat-session.packet-ids")
    val packetIds: List<PacketIdEntry> = emptyList(),
) {
    /**
     * 一条「起始协议号 → 数据包 id」映射。
     *
     * [fromProtocol] 是**协议号**而非版本号；同协议号的多版本（如 1.20.3 与 1.20.4 同为 765）
     * 共用一条，无需也无法分开登记。上界由下一条决定，最后一条无上界。
     */
    @ConfigSerializable
    data class PacketIdEntry(
        @Comment("config.chat-session.entry.from-protocol")
        val fromProtocol: Int = 0,

        @Comment("config.chat-session.entry.packet-id")
        val packetId: Int = 0,
    )
}
