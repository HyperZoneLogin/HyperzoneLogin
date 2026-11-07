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

package icu.h2l.api.db

import java.util.*

/**
 * 玩家可最终用于进入游戏区的一条游戏内身份记录。
 *
 * 该对象与 [User] 是“一对多”关系：
 * - [User.id] 是稳定的 HZL 账号标识；
 * - 本对象只保存某个游戏内名称与 UUID 的组合；
 * - 渠道来源不进入本表；同一身份可以被多个渠道共同使用。
 *
 * @property id 游戏内身份记录主键
 * @property userUUID 所属 HZL 账号
 * @property name 游戏内显示名称
 * @property uuid 游戏内 UUID
 */
data class InGameProfile(
    val id: UUID,
    val userUUID: UUID,
    val name: String,
    val uuid: UUID,
)
