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
import icu.h2l.api.log.info
import icu.h2l.api.log.warn
import icu.h2l.login.database.helper.InGameProfileDatabaseHelper
import icu.h2l.login.database.helper.UserDatabaseHelper
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.*

/**
 * 旧版账号/皮肤/认证表结构迁移器。
 *
 * 该迁移器只负责一次性结构升级：
 * - `profile` -> `hzl_user`；
 * - `profile_id` -> `user_uuid`；
 * - 删除旧版 profile 的 `name/uuid` 列；
 * - 删除旧版 in_game_profile 的渠道/时间戳列。
 *
 * 不负责日常读写，也不暴露旧表结构给 API。
 */
class LegacySchemaMigrator(
    private val manager: HyperZoneDatabaseManager,
    private val tablePrefix: String,
) {
    private val userTableName = "${tablePrefix}hzl_user"
    private val legacyUserTableName = "${tablePrefix}profile"
    private val inGameProfileTableName = "${tablePrefix}in_game_profile"

    fun migrateStructure() {
        renameLegacyUserTable()
        renameProfileIdColumns()
        dropObsoleteInGameProfileColumns()
    }

    fun migrateLegacyUserData(
        userDatabaseHelper: UserDatabaseHelper,
        inGameProfileDatabaseHelper: InGameProfileDatabaseHelper,
    ) {
        val columns = detectColumns(userTableName)
        if ("name" !in columns || "uuid" !in columns) {
            return
        }

        val legacyTable = LegacyUserIdentityTable(userTableName)
        val identities = manager.executeTransaction {
            legacyTable.select(legacyTable.id, legacyTable.name, legacyTable.uuid)
                .mapNotNull { row ->
                    val name = row[legacyTable.name]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val uuid = row[legacyTable.uuid] ?: return@mapNotNull null
                    LegacyUserIdentity(row[legacyTable.id], name, uuid)
                }
        }

        var migrated = 0
        identities.forEach { identity ->
            val existing = inGameProfileDatabaseHelper.listByProfileId(identity.userUUID)
            if (existing.isNotEmpty()) {
                userDatabaseHelper.setActiveInGameProfileId(identity.userUUID, existing.first().id)
                return@forEach
            }

            val created = inGameProfileDatabaseHelper.insert(
                userUUID = identity.userUUID,
                name = identity.name,
                uuid = identity.uuid,
            )
            userDatabaseHelper.setActiveInGameProfileId(identity.userUUID, created.id)
            migrated++
        }

        if (migrated > 0) {
            info { "旧版 User 身份迁移完成: migrated=$migrated" }
        }
    }

    fun dropLegacyUserColumns() {
        val legacyColumns = detectColumns(userTableName)
            .filter { name -> name == "name" || name == "uuid" }
            .toSet()
        if (legacyColumns.isNotEmpty()) {
            dropColumns(userTableName, legacyColumns, "旧版 user 兼容列")
        }
    }

    private fun renameLegacyUserTable() {
        if (!tableExists(legacyUserTableName) || tableExists(userTableName)) {
            return
        }

        executeRaw("ALTER TABLE ${quote(legacyUserTableName)} RENAME TO ${quote(userTableName)}")
        info { "已迁移旧版 profile 表: $legacyUserTableName -> $userTableName" }
    }

    private fun renameProfileIdColumns() {
        val tables = listOf(
            inGameProfileTableName,
            "${tablePrefix}binding_code",
            "${tablePrefix}skin_profile",
            "${tablePrefix}offline_auth",
            "${tablePrefix}floodgate_auth",
        )

        tables.forEach { tableName ->
            if (!tableExists(tableName)) {
                return@forEach
            }
            val columns = detectColumns(tableName)
            if ("profile_id" in columns && "user_uuid" !in columns) {
                executeRaw(
                    "ALTER TABLE ${quote(tableName)} RENAME COLUMN ${quote("profile_id")} TO ${quote("user_uuid")}"
                )
                info { "已迁移旧版列: $tableName.profile_id -> user_uuid" }
            }
        }
    }

    private fun dropObsoleteInGameProfileColumns() {
        if (!tableExists(inGameProfileTableName)) {
            return
        }
        val obsolete = detectColumns(inGameProfileTableName)
            .filter { it in OBSOLETE_IN_GAME_PROFILE_COLUMNS }
            .toSet()
        if (obsolete.isNotEmpty()) {
            dropColumns(inGameProfileTableName, obsolete, "旧版 in_game_profile 列")
        }
    }

    private fun dropColumns(tableName: String, columnNames: Set<String>, label: String) {
        manager.executeTransaction {
            val transaction = TransactionManager.current()
            val dialectName = transaction.db.dialect.name.lowercase(Locale.ROOT)
            val isSqlite = dialectName.contains("sqlite")
            if (!isSqlite && !transaction.db.supportsAlterTableWithDropColumn) {
                throw IllegalStateException(
                    "当前数据库不支持 ALTER TABLE DROP COLUMN，无法移除${label}: ${columnNames.joinToString()}"
                )
            }

            val table = dynamicTable(tableName)
            val quote: (String) -> String = { identifier ->
                transaction.db.identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(identifier)
            }

            knownLegacyIndexNames(tableName).forEach { indexName ->
                runCatching {
                    val sql = if (isSqlite) {
                        "DROP INDEX IF EXISTS ${quote(indexName)}"
                    } else {
                        "ALTER TABLE ${quote(tableName)} DROP INDEX ${quote(indexName)}"
                    }
                    transaction.exec(sql)
                }
            }

            val indexNames = transaction.connection.metadata {
                existingIndices(table).values
                    .flatten()
                    .filter { index ->
                        index.columns.any { column -> column.name.lowercase(Locale.ROOT) in columnNames }
                    }
                    .map { it.indexName }
                    .distinct()
            }

            indexNames.forEach { indexName ->
                val sql = if (isSqlite) {
                    "DROP INDEX IF EXISTS ${quote(indexName)}"
                } else {
                    "ALTER TABLE ${quote(tableName)} DROP INDEX ${quote(indexName)}"
                }
                transaction.exec(sql)
            }

            columnNames.sorted().forEach { columnName ->
                transaction.exec(
                    "ALTER TABLE ${quote(tableName)} DROP COLUMN ${quote(columnName)}"
                )
            }

            info { "已移除${label}: ${columnNames.sorted().joinToString()}" }
        }
    }

    private fun knownLegacyIndexNames(tableName: String): List<String> {
        return listOf(
            "${tableName}_name",
            "${tableName}_uuid",
            "${tableName}_profile_id",
            "${tableName}_user_uuid",
            "${tableName}_source",
            "${tablePrefix}profile_name",
            "${tablePrefix}profile_uuid",
        )
    }

    private fun detectColumns(tableName: String): Set<String> {
        if (!tableExists(tableName)) {
            return emptySet()
        }
        return manager.executeTransaction {
            TransactionManager.current().connection.metadata {
                columns(dynamicTable(tableName)).values
                    .flatten()
                    .map { column -> column.name.lowercase(Locale.ROOT) }
                    .toSet()
            }
        }
    }

    private fun tableExists(tableName: String): Boolean {
        return runCatching {
            manager.executeTransaction {
                TransactionManager.current().connection.metadata {
                    columns(dynamicTable(tableName)).values.flatten().isNotEmpty()
                }
            }
        }.getOrElse { false }
    }

    private fun executeRaw(sql: String) {
        manager.executeTransaction {
            TransactionManager.current().exec(sql)
        }
    }

    private fun quote(identifier: String): String {
        return manager.executeTransaction {
            TransactionManager.current().db.identifierManager
                .quoteIdentifierWhenWrongCaseOrNecessary(identifier)
        }
    }

    private fun dynamicTable(tableName: String): Table = object : Table(tableName) {}

    private data class LegacyUserIdentity(
        val userUUID: UUID,
        val name: String,
        val uuid: UUID,
    )

    private class LegacyUserIdentityTable(tableName: String) : Table(tableName) {
        val id = uuid("id")
        val name = varchar("name", 32).nullable()
        val uuid = uuid("uuid").nullable()
    }

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
