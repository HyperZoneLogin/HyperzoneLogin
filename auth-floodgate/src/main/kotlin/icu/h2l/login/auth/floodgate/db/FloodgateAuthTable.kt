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

package icu.h2l.login.auth.floodgate.db

import icu.h2l.api.db.table.ProfileTable
import org.jetbrains.exposed.sql.Table
import java.util.UUID

data class FloodgateAuthEntry(
    val id: Int,
    val name: String,
    val xuid: Long,
    val profileId: UUID,
)

class FloodgateAuthTable(prefix: String, profileTable: ProfileTable) : Table("${prefix}floodgate_auth") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 32)
    val xuid = long("xuid")
    val profileId = uuid("profile_id").references(profileTable.id)

    init {
        uniqueIndex("${tableName}_xuid", xuid)
        uniqueIndex("${tableName}_profile_id", profileId)
    }

    override val primaryKey = PrimaryKey(id)
}

