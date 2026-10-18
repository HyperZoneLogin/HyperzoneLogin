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

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.api.event.db.TableSchemaAction
import icu.h2l.api.event.db.TableSchemaEvent
import icu.h2l.api.log.info
import icu.h2l.api.log.warn
import icu.h2l.login.auth.online.api.db.EntryTable
import icu.h2l.login.auth.online.events.EntryRegisterEvent
import org.jetbrains.exposed.sql.SchemaUtils

/**
 * Entry 表管理器
 * 负责 Entry 表的注册、查询、创建、删除与事件处理
 */
class EntryTableManager(
    private val databaseManager: HyperZoneDatabaseManager,
    private val tablePrefix: String,
    private val profileTable: ProfileTable
) {
    private val entryTables = mutableMapOf<String, EntryTable>()

    @Suppress("DEPRECATION")
    private fun createEntryTable(entryTable: EntryTable) {
        databaseManager.executeTransaction {
            SchemaUtils.createMissingTablesAndColumns(entryTable, withLogs = false)
        }
    }

    fun registerEntry(entryId: String): EntryTable {
        val normalizedId = entryId.lowercase()
        return entryTables.getOrPut(normalizedId) {
            info { "注册入口表: $normalizedId" }
            EntryTable(normalizedId, tablePrefix, profileTable)
        }
    }

    fun getEntryTable(entryId: String): EntryTable? {
        return entryTables[entryId.lowercase()]
    }

    fun createAllEntryTables() {
        entryTables.values.forEach(::createEntryTable)
    }

   fun dropAllEntryTables() {
        databaseManager.executeTransaction {
            entryTables.values.forEach { entryTable ->
                SchemaUtils.drop(entryTable)
                warn { "已删除表: ${entryTable.tableName}" }
            }
        }
    }

    @Subscribe
    fun onSchemaEvent(event: TableSchemaEvent) {
        when (event.action) {
            TableSchemaAction.CREATE_ALL -> createAllEntryTables()
            TableSchemaAction.DROP_ALL -> dropAllEntryTables()
        }
    }

    @Subscribe
    fun onEntryRegister(event: EntryRegisterEvent) {
        info { "接收到 Entry 注册事件: ${event.configName} (ID: ${event.entryConfig.id})" }

        val entryTable = registerEntry(event.entryConfig.id)

        createEntryTable(entryTable)
    }
}
