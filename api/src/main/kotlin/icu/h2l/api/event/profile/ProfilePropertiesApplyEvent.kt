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
 * 在最终构造要发送给后端的正式 [GameProfile] 前触发的 properties 应用事件。
 *
 * 监听器可基于 [baseProfile] 与当前玩家上下文自由修改 [properties]，
 * 例如注入皮肤纹理、添加其他游戏属性等。
 *
 * 核心模块不关心 properties 的具体内容和数据结构，
 * 各功能模块根据自己的需求操作 [properties] 列表。
 *
 * @property hyperZonePlayer 当前登录态玩家对象
 * @property baseProfile 当前应用流程的基础档案（仅含 UUID 和用户名）
 * @property properties 可变的属性列表，监听器可自由添加、删除或替换
 */
@AwaitingEvent
class ProfilePropertiesApplyEvent(
    val hyperZonePlayer: HyperZonePlayer,
    val baseProfile: GameProfile,
    val properties: MutableList<GameProfile.Property>
)
