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

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.log.warn
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

class ProfileSkinProfileRepository(
    private val databaseManager: HyperZoneDatabaseManager,
    private val userTable: ProfileSkinUserTable
) {
    fun findSkinIdByProfileId(userUUID: UUID): UUID? {
        return databaseManager.executeTransaction {
            userTable.selectAll().where { userTable.userUUID eq userUUID }
                .limit(1)
                .map { row -> row[userTable.skinId] }
                .firstOrNull()
        }
    }

    /**
     * 把 source User 的皮肤关联迁移到 target User。
     *
     * `skin_profile.user_uuid` 是主键，因此 target 已有皮肤时保留 target，
     * 删除 source 的重复关联。
     */
    fun migrateUserBindings(sourceUserUUID: UUID, targetUserUUID: UUID): Boolean {
        if (sourceUserUUID == targetUserUUID) {
            return true
        }
        return try {
            databaseManager.executeTransaction {
                val sourceSkinId = userTable.selectAll().where { userTable.userUUID eq sourceUserUUID }
                    .limit(1)
                    .map { row -> row[userTable.skinId] }
                    .firstOrNull()
                    ?: return@executeTransaction true
                val targetSkinId = userTable.selectAll().where { userTable.userUUID eq targetUserUUID }
                    .limit(1)
                    .map { row -> row[userTable.skinId] }
                    .firstOrNull()

                if (targetSkinId == null) {
                    userTable.update({ userTable.userUUID eq sourceUserUUID }) {
                        it[userTable.userUUID] = targetUserUUID
                        it[userTable.skinId] = sourceSkinId
                        it[userTable.updatedAt] = System.currentTimeMillis()
                    }
                } else {
                    warn {
                        "皮肤合并：目标 User $targetUserUUID 已有皮肤关联，删除源 User $sourceUserUUID 的重复关联"
                    }
                    userTable.deleteWhere { userTable.userUUID eq sourceUserUUID }
                }
                true
            }
        } catch (e: Exception) {
            warn { "迁移皮肤绑定失败: ${e.message}" }
            false
        }
    }

    fun bindProfile(userUUID: UUID, skinId: UUID): Boolean {
        val existingSkinId = findSkinIdByProfileId(userUUID)
        if (existingSkinId == skinId) {
            return true
        }

        return try {
            if (existingSkinId == null) {
                databaseManager.executeTransaction {
                    userTable.insert {
                        it[userTable.userUUID] = userUUID
                        it[userTable.skinId] = skinId
                        it[userTable.updatedAt] = System.currentTimeMillis()
                    }
                }
            } else {
                databaseManager.executeTransaction {
                    userTable.update({ userTable.userUUID eq userUUID }) {
                        it[userTable.skinId] = skinId
                        it[userTable.updatedAt] = System.currentTimeMillis()
                    }
                }
            }
            true
        } catch (e: Exception) {
            warn { "写入皮肤 profile 关联失败: ${e.message}" }
            false
        }
    }
}

