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
import icu.h2l.login.auth.floodgate.api.db.FloodgateAuthEntry
import icu.h2l.login.auth.floodgate.api.db.FloodgateAuthTable
import icu.h2l.api.log.warn
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

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

    open fun getByProfileId(userUUID: UUID): FloodgateAuthEntry? {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.userUUID eq userUUID }
                .limit(1)
                .map(::toEntry)
                .firstOrNull()
        }
    }

    open fun findProfileIdByXuid(xuid: Long): UUID? {
        return getByXuid(xuid)?.userUUID
    }

    open fun createOrUpdate(name: String, xuid: Long, userUUID: UUID): Boolean {
        val displayName = name.trim()
        return try {
            databaseManager.executeTransaction {
                val byXuid = table.selectAll().where { table.xuid eq xuid }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull()
                val byProfileId = table.selectAll().where { table.userUUID eq userUUID }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull()

                if (byXuid != null && byXuid.userUUID != userUUID) {
                    warn { "Floodgate 绑定冲突：XUID $xuid 已绑定到其他 User: ${byXuid.userUUID}" }
                    return@executeTransaction false
                }
                if (byProfileId != null && byProfileId.xuid != xuid) {
                    warn { "Floodgate 绑定冲突：User $userUUID 已绑定到其他 Floodgate XUID: ${byProfileId.xuid}" }
                    return@executeTransaction false
                }

                if (byXuid == null && byProfileId == null) {
                    table.insert {
                        it[this.name] = displayName
                        it[this.xuid] = xuid
                        it[this.userUUID] = userUUID
                        it[this.gameUuid] = UUID(0L, xuid)
                    }
                    return@executeTransaction true
                }

                table.update({ table.xuid eq xuid }) {
                    it[this.name] = displayName
                    it[this.userUUID] = userUUID
                }
                true
            }
        } catch (e: Exception) {
            warn { "写入 Floodgate 认证记录失败: ${e.message}" }
            false
        }
    }

    /**
     * 把 source User 的 Floodgate 绑定迁移到 target User。
     *
     * 表内 `user_uuid` 唯一，因此 target 已经持有 Floodgate 绑定时不迁移，
     * 直接删除 source 的重复绑定，保留 target 的渠道账号。
     */
    open fun migrateUserBindings(sourceUserUUID: UUID, targetUserUUID: UUID): Boolean {
        if (sourceUserUUID == targetUserUUID) {
            return true
        }
        return try {
            databaseManager.executeTransaction {
                val targetEntry = table.selectAll().where { table.userUUID eq targetUserUUID }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull()
                if (targetEntry == null) {
                    table.update({ table.userUUID eq sourceUserUUID }) {
                        it[userUUID] = targetUserUUID
                    }
                } else {
                    warn {
                        "Floodgate 合并：目标 User $targetUserUUID 已有 Floodgate 绑定，删除源 User $sourceUserUUID 的重复绑定"
                    }
                    table.deleteWhere { table.userUUID eq sourceUserUUID }
                }
                true
            }
        } catch (e: Exception) {
            warn { "迁移 Floodgate 绑定失败: ${e.message}" }
            false
        }
    }

    /**
     * 删除指定 XUID 与 User 的 Floodgate 绑定。
     *
     * 仅删除渠道账号绑定行，不触碰 HZL User 与 InGameProfile。
     * XUID 与 User 必须同时匹配，避免误删其他账号的绑定。
     */
    open fun deleteByXuid(xuid: Long, userUUID: UUID): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.deleteWhere {
                    (table.xuid eq xuid) and (table.userUUID eq userUUID)
                } > 0
            }
        } catch (e: Exception) {
            warn { "删除 Floodgate 绑定失败: ${e.message}" }
            false
        }
    }

    open fun updateGameUuid(xuid: Long, gameUuid: UUID): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.xuid eq xuid }) {
                    it[this.gameUuid] = gameUuid
                } > 0
            }
        } catch (e: Exception) {
            warn { "更新 Floodgate 游戏 UUID 失败: ${e.message}" }
            false
        }
    }

    open fun updateEntryName(xuid: Long, newName: String): Boolean {
        val displayName = newName.trim()
        return try {
            databaseManager.executeTransaction {
                val existing = table.selectAll().where { table.xuid eq xuid }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull() ?: return@executeTransaction false
                if (existing.name == displayName) {
                    return@executeTransaction true
                }
                table.update({ table.xuid eq xuid }) {
                    it[name] = displayName
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
            userUUID = row[table.userUUID],
            gameUuid = row[table.gameUuid],
        )
    }
}


