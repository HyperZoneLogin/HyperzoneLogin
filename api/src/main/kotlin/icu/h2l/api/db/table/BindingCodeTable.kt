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
 * 绑定码表定义。
 *
 * 一条记录表示某个 User 当前可用的一次性绑定码。
 *
 * @param prefix 表名前缀
 * @param userTable HZL 账号表引用
 */
class BindingCodeTable(prefix: String, userTable: UserTable) : Table("${prefix}binding_code") {
    val code = varchar("code", 32)
    val userUUID = uuid("user_uuid").references(userTable.id).uniqueIndex()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(code)
}
