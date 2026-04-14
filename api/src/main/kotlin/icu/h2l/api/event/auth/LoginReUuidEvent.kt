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

package icu.h2l.api.event.auth

import icu.h2l.api.player.HyperZonePlayer

/**
 * 等待区中的登录会话主动请求重新生成“建档 UUID”后触发。
 *
 * 事件只携带当前事件发起者本身；
 * 各监听器必须仅处理该玩家当前会话，不得影响其他等待区玩家。
 *
 * @property hyperZonePlayer 发起 reUUID 的当前登录态玩家
 */
class LoginReUuidEvent(
    val hyperZonePlayer: HyperZonePlayer
)

