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

package icu.h2l.login.database

import icu.h2l.api.log.warn
import icu.h2l.login.manager.DatabaseManager
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

class BindingCodeRepository(
    private val databaseManager: DatabaseManager,
    private val table: BindingCodeTable
) : BindingCodeStore {
    override fun createOrReplace(code: String, profileId: UUID, createdAt: Long): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.deleteWhere { table.profileId eq profileId }
                table.insert {
                    it[this.code] = code
                    it[this.profileId] = profileId
                    it[this.createdAt] = createdAt
                }
            }
            true
        } catch (e: Exception) {
            warn { "创建绑定码失败: ${e.message}" }
            false
        }
    }

    override fun findProfileId(code: String): UUID? {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.code eq code }
                .limit(1)
                .map { it[table.profileId] }
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
}



