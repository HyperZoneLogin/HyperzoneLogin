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

package icu.h2l.login.database.migration

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.InGameProfileTable
import icu.h2l.api.db.table.LegacyUserTable
import icu.h2l.api.log.info
import icu.h2l.login.database.helper.InGameProfileDatabaseHelper
import icu.h2l.login.database.helper.UserDatabaseHelper
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.*

/**
 * 旧版 profile 表结构迁移器。
 *
 * 唯一职责：
 * - 把旧版 `profile.name/uuid` 转成 InGameProfile 并设置活动身份；
 * - 删除旧列及其索引。
 *
 * 不负责日常 User/InGameProfile 读写。
 */
class UserSchemaMigrator(
    private val manager: HyperZoneDatabaseManager,
    private val userDatabaseHelper: UserDatabaseHelper,
    private val inGameProfileDatabaseHelper: InGameProfileDatabaseHelper,
    tablePrefix: String,
) {
    private val legacyTable = LegacyUserTable(tablePrefix)
    private val inGameProfileTable = InGameProfileTable(tablePrefix)

    fun migrate() {
        dropObsoleteInGameProfileColumns()
        val legacyColumns = detectLegacyColumns()
        if ("name" in legacyColumns && "uuid" in legacyColumns) {
            migrateLegacyRows()
        }
        repairMissingActivePointers()
        if (legacyColumns.isNotEmpty()) {
            dropLegacyColumns(legacyColumns)
        }
    }

    private fun detectLegacyColumns(): Set<String> {
        return detectColumns(legacyTable)
            .filter { name -> name == "name" || name == "uuid" }
            .toSet()
    }

    private fun dropObsoleteInGameProfileColumns() {
        val obsolete = detectColumns(inGameProfileTable)
            .filter { name -> name in OBSOLETE_IN_GAME_PROFILE_COLUMNS }
            .toSet()
        if (obsolete.isNotEmpty()) {
            dropColumns(inGameProfileTable, obsolete, "旧版 in_game_profile 渠道列")
        }
    }

    private fun detectColumns(table: Table): Set<String> {
        return runCatching {
            manager.executeTransaction {
                TransactionManager.current().connection.metadata {
                    columns(table).values
                        .flatten()
                        .map { column -> column.name.lowercase(Locale.ROOT) }
                        .toSet()
                }
            }
        }.getOrElse {
            emptySet()
        }
    }

    private fun migrateLegacyRows() {
        val legacyValues = readLegacyValues()
        var migrated = 0

        legacyValues.forEach { (userUUID, identity) ->
            if (userDatabaseHelper.getRow(userUUID) == null) {
                userDatabaseHelper.insert(userUUID)
            }

            val existing = inGameProfileDatabaseHelper.listByProfileId(userUUID)
            if (existing.isNotEmpty()) {
                userDatabaseHelper.setActiveInGameProfileId(userUUID, existing.first().id)
                return@forEach
            }

            val created = inGameProfileDatabaseHelper.insert(
                userUUID = userUUID,
                name = identity.name,
                uuid = identity.uuid,
            )
            userDatabaseHelper.setActiveInGameProfileId(userUUID, created.id)
            migrated++
        }

        if (legacyValues.isNotEmpty()) {
            info { "HZL User 旧数据迁移完成: users=${legacyValues.size}, migrated=$migrated" }
        }
    }

    private fun readLegacyValues(): Map<UUID, LegacyIdentity> {
        return manager.executeTransaction {
            legacyTable.select(legacyTable.id, legacyTable.name, legacyTable.uuid).mapNotNull { row ->
                val name = row[legacyTable.name]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val uuid = row[legacyTable.uuid] ?: return@mapNotNull null
                row[legacyTable.id] to LegacyIdentity(name, uuid)
            }.toMap()
        }
    }

    private fun repairMissingActivePointers() {
        val rows = userDatabaseHelper.getAllRows()
        var activated = 0
        rows.forEach { row ->
            val explicit = row.activeInGameProfileId?.let(inGameProfileDatabaseHelper::getById)
            if (explicit != null && explicit.userUUID == row.id) {
                activated++
                return@forEach
            }

            val existing = inGameProfileDatabaseHelper.listByProfileId(row.id)
            if (existing.isNotEmpty()) {
                userDatabaseHelper.setActiveInGameProfileId(row.id, existing.first().id)
                activated++
            }
        }

        if (rows.isNotEmpty()) {
            info { "InGameProfile 活动身份检查完成: profiles=${rows.size}, active=$activated" }
        }
    }

    private fun dropLegacyColumns(legacyColumns: Set<String>) {
        dropColumns(legacyTable, legacyColumns, "旧版 profile 兼容列")
    }

    private fun dropColumns(table: Table, columnNames: Set<String>, label: String) {
        manager.executeTransaction {
            val transaction = TransactionManager.current()
            val dialectName = transaction.db.dialect.name.lowercase(Locale.ROOT)
            val isSqlite = dialectName.contains("sqlite")
            if (!isSqlite && !transaction.db.supportsAlterTableWithDropColumn) {
                throw IllegalStateException(
                    "当前数据库不支持 ALTER TABLE DROP COLUMN，无法移除${label}: ${columnNames.joinToString()}"
                )
            }

            val quote: (String) -> String = { identity ->
                transaction.db.identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(identity)
            }

            val indexNames = transaction.connection.metadata {
                existingIndices(table).values
                    .flatten()
                    .filter { index ->
                        index.columns.any { column ->
                            column.name.lowercase(Locale.ROOT) in columnNames
                        }
                    }
                    .map { it.indexName }
                    .distinct()
            }

            indexNames.forEach { indexName ->
                val sql = if (isSqlite) {
                    "DROP INDEX IF EXISTS ${quote(indexName)}"
                } else {
                    "ALTER TABLE ${quote(table.tableName)} DROP INDEX ${quote(indexName)}"
                }
                transaction.exec(sql)
            }

            columnNames.sorted().forEach { columnName ->
                transaction.exec(
                    "ALTER TABLE ${quote(table.tableName)} DROP COLUMN ${quote(columnName)}"
                )
            }

            info { "已移除${label}: ${columnNames.sorted().joinToString()}" }
        }
    }

    private data class LegacyIdentity(
        val name: String,
        val uuid: UUID,
    )

    companion object {
        private val OBSOLETE_IN_GAME_PROFILE_COLUMNS = setOf(
            "channel",
            "channel_entry",
            "channel_account_id",
            "created_at",
            "updated_at",
        )
    }
}
