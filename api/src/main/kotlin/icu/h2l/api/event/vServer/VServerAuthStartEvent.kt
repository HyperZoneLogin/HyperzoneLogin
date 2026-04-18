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

package icu.h2l.api.event.vServer

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.player.HyperZonePlayer

/**
 * 玩家进入等待区实现并开始认证流程前触发的事件。
 *
 * 该事件只用于通知“等待区认证即将开始”；
 * 不再承载是否允许离开等待区的结果。
 *
 * 玩家是否可以离开等待区，统一以当前登录态是否仍在 waiting area
 *（例如是否已 attach Profile）为准。
 *
 * @property proxyPlayer 当前代理层玩家对象
 * @property hyperZonePlayer 当前登录态玩家对象
 */
class VServerAuthStartEvent(
    val proxyPlayer: Player,
    val hyperZonePlayer: HyperZonePlayer
)
