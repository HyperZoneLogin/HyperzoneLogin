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

package icu.h2l.login.profile.skin.db

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.event.db.TableSchemaAction
import icu.h2l.api.event.db.TableSchemaEvent
import icu.h2l.api.log.warn
import org.jetbrains.exposed.sql.SchemaUtils

class ProfileSkinCacheTableManager(
    private val databaseManager: HyperZoneDatabaseManager,
    val cacheTable: ProfileSkinCacheTable,
    val profileTable: ProfileSkinProfileTable
) {
    @Suppress("DEPRECATION")
    fun createTable() {
        databaseManager.executeTransaction {
            // 这里先用 Exposed 的补表补列能力平滑升级既有安装，后续如引入正式迁移框架再替换。
            SchemaUtils.createMissingTablesAndColumns(cacheTable, profileTable, withLogs = false)
        }
    }

    fun dropTable() {
        databaseManager.executeTransaction {
            SchemaUtils.drop(profileTable, cacheTable)
            warn { "已删除表: ${profileTable.tableName}" }
            warn { "已删除表: ${cacheTable.tableName}" }
        }
    }

    @Subscribe
    fun onSchemaEvent(event: TableSchemaEvent) {
        when (event.action) {
            TableSchemaAction.CREATE_ALL -> createTable()
            TableSchemaAction.DROP_ALL -> dropTable()
        }
    }
}
