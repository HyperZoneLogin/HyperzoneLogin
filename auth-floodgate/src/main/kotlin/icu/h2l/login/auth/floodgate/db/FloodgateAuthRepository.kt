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

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.log.warn
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

open class FloodgateAuthRepository(
    private val databaseManager: HyperZoneDatabaseManager,
    private val table: FloodgateAuthTable,
) {
    open fun getByXuid(xuid: Long): FloodgateAuthEntry? {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.xuid eq xuid }
                .limit(1)
                .map(::toEntry)
                .firstOrNull()
        }
    }

    open fun getByProfileId(profileId: UUID): FloodgateAuthEntry? {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.profileId eq profileId }
                .limit(1)
                .map(::toEntry)
                .firstOrNull()
        }
    }

    open fun findProfileIdByXuid(xuid: Long): UUID? {
        return getByXuid(xuid)?.profileId
    }

    open fun createOrUpdate(name: String, xuid: Long, profileId: UUID): Boolean {
        val normalizedName = name.lowercase()
        return try {
            databaseManager.executeTransaction {
                val byXuid = table.selectAll().where { table.xuid eq xuid }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull()
                val byProfileId = table.selectAll().where { table.profileId eq profileId }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull()

                if (byXuid != null && byXuid.profileId != profileId) {
                    warn { "Floodgate 绑定冲突：XUID $xuid 已绑定到其他 Profile: ${byXuid.profileId}" }
                    return@executeTransaction false
                }
                if (byProfileId != null && byProfileId.xuid != xuid) {
                    warn { "Floodgate 绑定冲突：Profile $profileId 已绑定到其他 Floodgate XUID: ${byProfileId.xuid}" }
                    return@executeTransaction false
                }

                if (byXuid == null && byProfileId == null) {
                    table.insert {
                        it[this.name] = normalizedName
                        it[this.xuid] = xuid
                        it[this.profileId] = profileId
                    }
                    return@executeTransaction true
                }

                table.update({ table.xuid eq xuid }) {
                    it[this.name] = normalizedName
                    it[this.profileId] = profileId
                }
                true
            }
        } catch (e: Exception) {
            warn { "写入 Floodgate 认证记录失败: ${e.message}" }
            false
        }
    }

    open fun updateEntryName(xuid: Long, newName: String): Boolean {
        val normalizedName = newName.lowercase()
        return try {
            databaseManager.executeTransaction {
                val existing = table.selectAll().where { table.xuid eq xuid }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull() ?: return@executeTransaction false
                if (existing.name == normalizedName) {
                    return@executeTransaction true
                }
                table.update({ table.xuid eq xuid }) {
                    it[name] = normalizedName
                } > 0
            }
        } catch (e: Exception) {
            warn { "更新 Floodgate 认证名称失败: ${e.message}" }
            false
        }
    }

    private fun toEntry(row: ResultRow): FloodgateAuthEntry {
        return FloodgateAuthEntry(
            id = row[table.id],
            name = row[table.name],
            xuid = row[table.xuid],
            profileId = row[table.profileId],
        )
    }
}


