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
 * 游戏内身份表（InGameProfile）。
 *
 * 一条记录代表“某个 HZL 账号下的一组游戏内名称/UUID”。
 * 同一账号允许存在多条记录，包括：
 * - 同名不同 UUID；
 * - 不同名不同 UUID。
 *
 * 渠道来源不属于身份本身，因此本表不保存 channel/entry/account 字段。
 * 多个渠道只要返回相同的 UUID 与名称，就应复用同一条记录。
 *
 * 全局唯一性：UUID 在整个表中唯一（含同一账号内部），名称不得跨账号重复。
 * 数据库层通过唯一索引保证 UUID 不重复，名称策略由核心业务层检查。
 *
 * @param prefix 表名前缀
 * @param userTable HZL 账号表引用
 */
class InGameProfileTable(
    prefix: String = "",
    userTable: UserTable = UserTable(prefix),
) : Table("${prefix}in_game_profile") {
    /**
     * 游戏内身份记录主键。
     */
    val id = uuid("id")

    /**
     * 所属 HZL 账号。
     */
    val userUUID = uuid("user_uuid").references(userTable.id)

    /**
     * 游戏内显示名称（保留原始大小写）。
     */
    val name = varchar("name", 32)

    /**
     * 游戏内显示名称的小写形式，用于跨账号冲突检查。
     */
    val nameLower = varchar("name_lower", 32)

    /**
     * 游戏内 UUID，作为跨渠道复用同一身份的唯一依据。
     */
    val uuid = uuid("uuid")

    init {
        index(false, userUUID)
        uniqueIndex("${tableName}_uuid_unique", uuid)
        index(false, nameLower)
    }

    override val primaryKey = PrimaryKey(id)
}
