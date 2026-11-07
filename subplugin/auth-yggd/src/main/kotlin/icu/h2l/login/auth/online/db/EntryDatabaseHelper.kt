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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

data class EntryRecord(
    val entryId: String,
    val name: String,
    val uuid: UUID,
    val userUUID: UUID,
)

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

            val distinctUserUUIDs = matchedProfileIds.distinct()
            if (distinctUserUUIDs.size > 1) {
                warn { "Entry $entryId 中 UUID=$uuid 存在多个 userUUID 绑定: $distinctUserUUIDs" }
            }

            distinctUserUUIDs.firstOrNull()
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
                            "创建入口记录失败: Entry $entryId 中 UUID=$uuid 已绑定到其他 userUUID=${existingBindings.map { it.first }.distinct()}，拒绝改绑到 $pid"
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

    /**
     * 删除指定入口、UUID 与 User 完全匹配的单条绑定。
     *
     * 只删除渠道入口绑定，不触碰 HZL User 与 InGameProfile。
     */
    fun deleteEntry(entryId: String, uuid: UUID, pid: UUID): Boolean {
        val entryTable = entryTableManager.getEntryTable(entryId) ?: return false

        return try {
            databaseManager.executeTransaction {
                entryTable.deleteWhere {
                    (entryTable.uuid eq uuid) and (entryTable.pid eq pid)
                } > 0
            }
        } catch (e: Exception) {
            warn { "删除入口记录失败: ${e.message}" }
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

    fun findEntriesByProfileId(entryId: String, userUUID: UUID): List<EntryRecord> {
        val entryTable = entryTableManager.getEntryTable(entryId) ?: return emptyList()

        return databaseManager.executeTransaction {
            entryTable.selectAll().where { entryTable.pid eq userUUID }
                .map { row ->
                    EntryRecord(
                        entryId = entryId.lowercase(),
                        name = row[entryTable.name],
                        uuid = row[entryTable.uuid],
                        userUUID = row[entryTable.pid],
                    )
                }
        }
    }

    /**
     * 把所有已注册入口表中属于 source User 的绑定迁移到 target User。
     *
     * 入口表对 `(name, uuid)` 有唯一约束；若 target 已有相同 `(name, uuid)`，
     * 则删除 source 的重复行，避免迁移产生唯一键冲突。
     */
    fun migrateUserBindings(sourceUserUUID: UUID, targetUserUUID: UUID): Boolean {
        if (sourceUserUUID == targetUserUUID) {
            return true
        }
        return try {
            entryTableManager.getEntryIds().forEach { entryId ->
                val entryTable = entryTableManager.getEntryTable(entryId) ?: return@forEach
                databaseManager.executeTransaction {
                    val targetKeys = entryTable.selectAll().where { entryTable.pid eq targetUserUUID }
                        .map { row -> row[entryTable.name] to row[entryTable.uuid] }
                        .toSet()
                    val sourceRows = entryTable.selectAll().where { entryTable.pid eq sourceUserUUID }
                        .map { row -> Triple(row[entryTable.id], row[entryTable.name], row[entryTable.uuid]) }
                    sourceRows.forEach { (id, name, uuid) ->
                        if ((name to uuid) in targetKeys) {
                            warn {
                                "Yggdrasil 合并：目标 User $targetUserUUID 已存在入口 $entryId 的相同绑定，删除源记录"
                            }
                            entryTable.deleteWhere { entryTable.id eq id }
                        } else {
                            entryTable.update({ entryTable.id eq id }) {
                                it[pid] = targetUserUUID
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            warn { "迁移 Yggdrasil 入口绑定失败: ${e.message}" }
            false
        }
    }

    fun findProfileIdsByUuid(entryIds: Collection<String>, uuid: UUID): Set<UUID> {
        val result = linkedSetOf<UUID>()
        entryIds.forEach { entryId ->
            val entryTable = entryTableManager.getEntryTable(entryId) ?: return@forEach
            databaseManager.executeTransaction {
                entryTable.selectAll().where { entryTable.uuid eq uuid }
                    .mapTo(result) { row -> row[entryTable.pid] }
            }
        }
        return result
    }
}
