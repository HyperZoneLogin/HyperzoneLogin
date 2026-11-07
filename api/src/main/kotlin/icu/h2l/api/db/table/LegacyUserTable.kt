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
 * 旧版 profile 表迁移视图。
 *
 * 该表对象只用于启动迁移：
 * - 检测并读取旧版 `name` / `uuid` 列；
 * - 迁移完成后这些列会被删除；
 * - 不参与新库建表，也不属于稳定 API 表结构。
 */
class LegacyUserTable(prefix: String) : Table("${prefix}profile") {
    val id = uuid("id")
    val name = varchar("name", 32).nullable()
    val uuid = uuid("uuid").nullable()
    val activeInGameProfileId = uuid("active_in_game_profile_id").nullable()

    override val primaryKey = PrimaryKey(id)
}
