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

import icu.h2l.api.db.table.BindingCodeTable
import icu.h2l.api.log.warn
import icu.h2l.login.database.DatabaseManager
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * 绑定码表的单表数据访问 helper。
 *
 * 只负责 binding_code 表的读写，不包含 User 业务判断。
 */
class BindingCodeDatabaseHelper(
    private val databaseManager: DatabaseManager,
    private val table: BindingCodeTable
) : BindingCodeStore {
    override fun replace(code: String, userUUID: UUID, createdAt: Long): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.deleteWhere { table.userUUID eq userUUID }
                table.insert {
                    it[this.code] = code
                    it[this.userUUID] = userUUID
                    it[this.createdAt] = createdAt
                }
            }
            true
        } catch (e: Exception) {
            warn { "创建绑定码失败: ${e.message}" }
            false
        }
    }

    override fun findCode(userUUID: UUID): String? {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.userUUID eq userUUID }
                .limit(1)
                .map { it[table.code] }
                .firstOrNull()
        }
    }

    override fun findProfileId(code: String): UUID? {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.code eq code }
                .limit(1)
                .map { it[table.userUUID] }
                .firstOrNull()
        }
    }

    override fun consume(code: String): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.deleteWhere { table.code eq code }
            } > 0
        } catch (e: Exception) {
            warn { "销毁绑定码失败: ${e.message}" }
            false
        }
    }

    override fun delete(userUUID: UUID): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.deleteWhere { table.userUUID eq userUUID }
            }
            true
        } catch (e: Exception) {
            warn { "删除绑定码失败: ${e.message}" }
            false
        }
    }

    override fun reassignUser(sourceUserUUID: UUID, targetUserUUID: UUID): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.userUUID eq sourceUserUUID }) {
                    it[userUUID] = targetUserUUID
                }
            } > 0
        } catch (e: Exception) {
            warn { "迁移绑定码失败: ${e.message}" }
            false
        }
    }
}



