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

package icu.h2l.api.db.table

import org.jetbrains.exposed.sql.Table

/**
 * HZL 账号表定义。
 *
 * 该表只保存稳定的账号 ID 与“当前活动游戏身份”指针：
 * - 账号 ID 不再直接作为游戏内 UUID；
 * - 游戏内名称/UUID 统一由 [InGameProfileTable] 管理；
 * - [activeInGameProfileId] 指向当前登录游戏区时使用的游戏内身份。
 *
 * 旧版本的 `name` / `uuid` 列会在启动迁移中转换为 InGameProfile 后删除。
 *
 * @param prefix 表名前缀，默认为空字符串
 */
class UserTable(prefix: String = "") : Table("${prefix}hzl_user") {
    /**
     * HZL 账号主键，用于和各认证入口建立映射。
     */
    val id = uuid("id")

    /**
     * 当前活动游戏内身份。
     *
     * 不声明数据库外键，避免 profile 与 in_game_profile 互相引用造成建表顺序问题；
     * 指针有效性由核心层维护。
     */
    val activeInGameProfileId = uuid("active_in_game_profile_id").nullable()

    /**
     * 表主键定义。
     */
    override val primaryKey = PrimaryKey(id)
}
