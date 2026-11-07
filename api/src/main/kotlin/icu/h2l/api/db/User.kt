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
 * [icu.h2l.api.db.table.UserTable] 对应的 HZL 账号对象。
 *
 * 该对象只表示账号本身：
 * - [id] 是稳定的 HZL User UUID；
 * - 游戏内名称 / UUID 不在本对象中投影，必须显式读取
 *   [icu.h2l.api.db.InGameProfile]（通常是当前活动身份）。
 *
 * @property id 稳定 HZL User UUID
 * @property activeInGameProfileId 当前活动游戏内身份记录 ID；未设置时为 null
 */
data class User(
    val id: UUID,
    val activeInGameProfileId: UUID? = null
)
