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

package icu.h2l.login.auth.online.api.db

import icu.h2l.api.db.table.ProfileTable
import org.jetbrains.exposed.sql.Table

/**
 * 入口表基类
 * 用于不同的登录入口（如 mojang、offline 等）
 *
 * @param entryId 入口ID，全小写，如 "mojang"、"offline"
 * @param prefix 表名前缀，默认为空字符串
 * @param profileTable 档案表实例，用于外键关联
 */
class EntryTable(entryId: String, prefix: String = "", profileTable: ProfileTable) : Table("${prefix}entry_$entryId") {
    /**
     * 自增ID
     */
    val id = integer("id").autoIncrement()

    /**
     * 入口处的玩家名
     */
    val name = varchar("name", 32)

    /**
     * 入口处的UUID
     */
    val uuid = uuid("uuid")

    /**
     * 映射的档案ID（外键）
     */
    val pid = uuid("pid").references(profileTable.id)

    override val primaryKey = PrimaryKey(id)

    init {
        // 确保 name + uuid 的组合是唯一的
        uniqueIndex(name, uuid)
    }
}