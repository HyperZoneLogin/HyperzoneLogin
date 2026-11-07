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

package icu.h2l.login.merge.service

import icu.h2l.api.db.InGameProfile
import icu.h2l.api.db.table.InGameProfileTable
import icu.h2l.api.db.table.UserTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * data-merge 共用的账号/游戏内身份写入器。
 *
 * 查询、插入、更新全部拆成独立方法；调用方必须显式决定走 create 还是 update。
 *
 * 注意：InGameProfile 的 `name` / `uuid` / 渠道来源写入后不可变；
 * 本写入器不提供覆盖既有游戏内身份的更新方法。
 *
 * 调用方必须在数据库事务内使用。
 */
internal class MergeUserWriter(
    private val userTable: UserTable,
    private val inGameProfileTable: InGameProfileTable,
) {
    fun findProfileId(profileName: String, gameUuid: UUID): UUID? {
        val existingByUuid = inGameProfileTable.selectAll()
            .where { inGameProfileTable.uuid eq gameUuid }
            .limit(1)
            .firstOrNull()
        if (existingByUuid != null) {
            return existingByUuid[inGameProfileTable.userUUID]
        }

        return inGameProfileTable.selectAll()
            .where { inGameProfileTable.nameLower eq normalizedName(profileName) }
            .limit(1)
            .firstOrNull()
            ?.get(inGameProfileTable.userUUID)
    }

    fun createProfile(profileName: String, gameUuid: UUID): UUID {
        val userUUID = UUID.randomUUID()
        userTable.insert {
            it[id] = userUUID
            it[activeInGameProfileId] = null
        }
        createIdentity(userUUID, profileName, gameUuid)
        return userUUID
    }

    fun findIdentity(userUUID: UUID, gameUuid: UUID): InGameProfile? {
        return inGameProfileTable.selectAll().where {
            (inGameProfileTable.userUUID eq userUUID) and
                (inGameProfileTable.uuid eq gameUuid)
        }
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                InGameProfile(
                    id = row[inGameProfileTable.id],
                    userUUID = userUUID,
                    name = row[inGameProfileTable.name],
                    uuid = row[inGameProfileTable.uuid],
                )
            }
    }

    fun createIdentity(userUUID: UUID, profileName: String, gameUuid: UUID): InGameProfile {
        val name = profileName.trim()
        val normalized = normalizedName(name)
        val inGameProfileId = UUID.randomUUID()

        inGameProfileTable.insert {
            it[id] = inGameProfileId
            it[inGameProfileTable.userUUID] = userUUID
            it[inGameProfileTable.name] = name
            it[inGameProfileTable.nameLower] = normalized
            it[inGameProfileTable.uuid] = gameUuid
        }

        activateIfUnset(userUUID, inGameProfileId)
        return InGameProfile(
            id = inGameProfileId,
            userUUID = userUUID,
            name = name,
            uuid = gameUuid,
        )
    }

    private fun activateIfUnset(userUUID: UUID, inGameProfileId: UUID) {
        val activeInGameProfileId = userTable.selectAll()
            .where { userTable.id eq userUUID }
            .limit(1)
            .map { it[userTable.activeInGameProfileId] }
            .firstOrNull()
        if (activeInGameProfileId == null) {
            userTable.update({ userTable.id eq userUUID }) {
                it[userTable.activeInGameProfileId] = inGameProfileId
            }
        }
    }

    private fun normalizedName(profileName: String): String {
        return profileName.trim().lowercase(Locale.ROOT)
    }
}
