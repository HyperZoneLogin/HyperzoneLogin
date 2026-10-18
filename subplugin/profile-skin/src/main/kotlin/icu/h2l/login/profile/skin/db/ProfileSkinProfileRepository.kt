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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

class ProfileSkinProfileRepository(
    private val databaseManager: HyperZoneDatabaseManager,
    private val profileTable: ProfileSkinProfileTable
) {
    fun findSkinIdByProfileId(profileId: UUID): UUID? {
        return databaseManager.executeTransaction {
            profileTable.selectAll().where { profileTable.profileId eq profileId }
                .limit(1)
                .map { row -> row[profileTable.skinId] }
                .firstOrNull()
        }
    }

    fun bindProfile(profileId: UUID, skinId: UUID): Boolean {
        val existingSkinId = findSkinIdByProfileId(profileId)
        if (existingSkinId == skinId) {
            return true
        }

        return try {
            if (existingSkinId == null) {
                databaseManager.executeTransaction {
                    profileTable.insert {
                        it[profileTable.profileId] = profileId
                        it[profileTable.skinId] = skinId
                        it[profileTable.updatedAt] = System.currentTimeMillis()
                    }
                }
            } else {
                databaseManager.executeTransaction {
                    profileTable.update({ profileTable.profileId eq profileId }) {
                        it[profileTable.skinId] = skinId
                        it[profileTable.updatedAt] = System.currentTimeMillis()
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

