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
import icu.h2l.api.db.User
import icu.h2l.api.player.HyperZonePlayer

/**
 * 当前登录态玩家成功 attach 到正式 [User] 后触发的事件。
 *
 * 该事件用于把认证链路中拿到的临时状态正式落到已 attach 的档案上下文中。
 *
 * @property hyperZonePlayer 当前登录态玩家对象
 * @property profile 刚刚 attach 成功的正式档案
 */
@AwaitingEvent
class UserAttachedEvent(
    val hyperZonePlayer: HyperZonePlayer,
    val profile: User
)

