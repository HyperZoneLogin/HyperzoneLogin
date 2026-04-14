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

package icu.h2l.api.event.profile

import com.velocitypowered.api.event.annotation.AwaitingEvent
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.player.HyperZonePlayer
import java.util.UUID

/**
 * 在代理层即将向客户端写出登录成功包时触发。
 *
 * 该事件对齐 `ServerLoginSuccessPacket` 的主要可变字段：`uuid`、`username`、`properties`。
 * 监听器可先修改这些可写字段，再通过把 [rewritePacket] 设为 `true`，声明需要把整份变更写回原始登录成功包。
 *
 * @property hyperZonePlayer 当前登录态玩家对象
 * @property currentUuid 当前登录成功包里原本要发送的 UUID
 * @property currentUsername 当前登录成功包里原本要发送的用户名
 * @property currentProperties 当前登录成功包里原本要发送的属性列表快照
 */
@AwaitingEvent
class ServerLoginSuccessEvent(
    val hyperZonePlayer: HyperZonePlayer,
    val currentUuid: UUID,
    val currentUsername: String,
    currentProperties: List<GameProfile.Property>?
) {
    /**
     * 当前登录成功包里原本要发送的属性列表快照。
     */
    val currentProperties: List<GameProfile.Property>? = currentProperties?.toList()

    /**
     * 是否需要把事件中的可写字段整体回写到登录成功包。
     */
    var rewritePacket: Boolean = false

    /**
     * 当 [rewritePacket] 为 `true` 时要写入登录成功包的 UUID。
     * 默认值为当前包内 UUID。
     */
    var uuid: UUID = currentUuid

    /**
     * 当 [rewritePacket] 为 `true` 时要写入登录成功包的用户名。
     * 默认值为当前包内用户名。
     */
    var username: String = currentUsername

    /**
     * 当 [rewritePacket] 为 `true` 时要写入登录成功包的属性列表。
     * 默认值为当前包内属性列表快照。
     */
    var properties: List<GameProfile.Property>? = currentProperties?.toList()
}

