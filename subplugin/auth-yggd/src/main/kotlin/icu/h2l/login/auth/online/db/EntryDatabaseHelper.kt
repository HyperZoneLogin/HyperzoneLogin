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

package icu.h2l.login.auth.online.db

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.log.warn
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * Entry 表数据操作帮助类
 */
class EntryDatabaseHelper(
    private val databaseManager: HyperZoneDatabaseManager,
    private val entryTableManager: EntryTableManager
) {
    fun findEntryByUuid(entryId: String, uuid: UUID): UUID? {
        val entryTable = entryTableManager.getEntryTable(entryId) ?: return null

        return databaseManager.executeTransaction {
            val matchedProfileIds = entryTable.selectAll().where { entryTable.uuid eq uuid }
                .map { it[entryTable.pid] }

            val distinctProfileIds = matchedProfileIds.distinct()
            if (distinctProfileIds.size > 1) {
                warn { "Entry $entryId 中 UUID=$uuid 存在多个 profileId 绑定: $distinctProfileIds" }
            }

            distinctProfileIds.firstOrNull()
        }
    }

    fun createEntry(entryId: String, name: String, uuid: UUID, pid: UUID): Boolean {
        val entryTable = entryTableManager.getEntryTable(entryId) ?: return false

        return try {
            databaseManager.executeTransaction {
                val existingBindings = entryTable.selectAll().where { entryTable.uuid eq uuid }
                    .map { row -> row[entryTable.pid] to row[entryTable.name] }

                when {
                    existingBindings.isEmpty() -> {
                        entryTable.insert {
                            it[entryTable.name] = name
                            it[entryTable.uuid] = uuid
                            it[entryTable.pid] = pid
                        }
                    }

                    existingBindings.any { (existingPid, _) -> existingPid != pid } -> {
                        warn {
                            "创建入口记录失败: Entry $entryId 中 UUID=$uuid 已绑定到其他 profileId=${existingBindings.map { it.first }.distinct()}，拒绝改绑到 $pid"
                        }
                        return@executeTransaction false
                    }

                    existingBindings.any { (_, existingName) -> existingName != name } -> {
                        entryTable.update({ entryTable.uuid eq uuid }) {
                            it[entryTable.name] = name
                        }
                    }
                }

                true
            }
        } catch (e: Exception) {
            warn { "创建入口记录失败: ${e.message}" }
            false
        }
    }

    fun updateEntryName(entryId: String, uuid: UUID, newName: String): Boolean {
        val entryTable = entryTableManager.getEntryTable(entryId) ?: return false

        return try {
            databaseManager.executeTransaction {
                val existingNames = entryTable.selectAll().where { entryTable.uuid eq uuid }
                    .map { it[entryTable.name] }

                when {
                    existingNames.isEmpty() -> false
                    existingNames.all { it == newName } -> true
                    else -> entryTable.update({ entryTable.uuid eq uuid }) {
                        it[name] = newName
                    } > 0
                }
            }
        } catch (e: Exception) {
            warn { "更新入口名称失败: ${e.message}" }
            false
        }
    }

    fun verifyEntry(entryId: String, uuid: UUID): Boolean {
        val entryTable = entryTableManager.getEntryTable(entryId) ?: return false

        return databaseManager.executeTransaction {
            entryTable.selectAll().where { entryTable.uuid eq uuid }.count() > 0
        }
    }
}
