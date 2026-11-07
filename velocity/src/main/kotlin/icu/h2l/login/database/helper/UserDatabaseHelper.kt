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

package icu.h2l.login.database.helper

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.UserTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * profile 表的数据访问层。
 *
 * 只负责 HZL 账号行本身的读写，不处理 InGameProfile、冲突检查或活动身份解析。
 */
class UserDatabaseHelper(
    private val manager: HyperZoneDatabaseManager,
    private val table: UserTable,
) {
    fun getRow(userUUID: UUID): UserRow? {
        return manager.executeTransaction {
            table.selectAll().where { table.id eq userUUID }
                .limit(1)
                .map(::toRow)
                .firstOrNull()
        }
    }

    fun getAllRows(): List<UserRow> {
        return manager.executeTransaction {
            table.selectAll().map(::toRow)
        }
    }

    fun insert(userUUID: UUID) {
        manager.executeTransaction {
            table.insert {
                it[table.id] = userUUID
                it[activeInGameProfileId] = null
            }
        }
    }

    fun delete(userUUID: UUID): Boolean {
        return manager.executeTransaction {
            table.deleteWhere { table.id eq userUUID } > 0
        }
    }

    fun getActiveInGameProfileId(userUUID: UUID): UUID? {
        return getRow(userUUID)?.activeInGameProfileId
    }

    fun setActiveInGameProfileId(userUUID: UUID, inGameProfileId: UUID) {
        val updated = manager.executeTransaction {
            table.update({ table.id eq userUUID }) {
                it[activeInGameProfileId] = inGameProfileId
            }
        }
        if (updated == 0) {
            throw IllegalStateException("未找到 User: $userUUID")
        }
    }

    private fun toRow(row: ResultRow): UserRow {
        return UserRow(
            id = row[table.id],
            activeInGameProfileId = row[table.activeInGameProfileId],
        )
    }
}

/**
 * profile 表单行数据。
 */
data class UserRow(
    val id: UUID,
    val activeInGameProfileId: UUID?,
)
