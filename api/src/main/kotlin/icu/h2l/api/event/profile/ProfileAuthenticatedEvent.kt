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

/**
 * 在认证模块完成外部鉴权后、正式档案 attach 完成前触发的认证完成事件。
 *
 * 该事件适合各功能模块根据外部认证结果提前准备相关数据，
 * 例如皮肤模块可从 [authenticatedProfile] 中提取皮肤信息并预处理。
 *
 * 核心层和认证模块不关心各功能模块如何处理这个事件，
 * 只负责在认证成功后触发通知。
 *
 * @property hyperZonePlayer 当前登录态玩家对象
 * @property authenticatedProfile 外部认证返回的完整档案
 * @property entryId 命中的认证入口标识
 * @property serverUrl 本次认证使用的服务端地址
 */
@AwaitingEvent
class ProfileAuthenticatedEvent(
    val hyperZonePlayer: HyperZonePlayer,
    val authenticatedProfile: GameProfile,
    val entryId: String,
    val serverUrl: String
)
