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

package icu.h2l.login.database

import icu.h2l.api.db.Profile
import icu.h2l.api.log.warn
import icu.h2l.login.manager.DatabaseManager
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Profile 数据库访问帮助类。
 *
 * 默认职责边界：
 * - 创建新 Profile；
 * - 读取既有 Profile；
 * - 提供围绕创建/读取流程所需的受控业务辅助能力。
 *
 * 重要约束：
 * - 除非需求被明确强调，否则不要在这里新增“修改现有 Profile 正式数据”的接口；
 * - 特别是不要把运行时 GameProfile 替换、Velocity 内存索引补偿、等待区临时态修正，误实现为这里的数据库更新；
 * - 这里不是现有 Profile name / UUID 热修改入口。
 */
class DatabaseHelper(
    private val manager: DatabaseManager
) {
    private val profileTable = manager.getProfileTable()

    private val profileCacheById = ConcurrentHashMap<UUID, Profile>()
    private val profileCacheByName = ConcurrentHashMap<String, Profile>()
    private val profileCacheByUuid = ConcurrentHashMap<UUID, Profile>()

    private fun cacheProfile(profile: Profile) {
        profileCacheById[profile.id] = profile
        profileCacheByName[profile.name.lowercase()] = profile
        profileCacheByUuid[profile.uuid] = profile
    }


    private fun loadProfileById(profileId: UUID): Profile? {
        return manager.executeTransaction {
            profileTable.selectAll().where { profileTable.id eq profileId }
                .limit(1)
                .map {
                    Profile(
                        id = it[profileTable.id],
                        name = it[profileTable.name],
                        uuid = it[profileTable.uuid]
                    )
                }
                .firstOrNull()
        }
    }

    private fun loadProfileByName(name: String): Profile? {
        return manager.executeTransaction {
            profileTable.selectAll().where { profileTable.name eq name }
                .limit(1)
                .map {
                    Profile(
                        id = it[profileTable.id],
                        name = it[profileTable.name],
                        uuid = it[profileTable.uuid]
                    )
                }
                .firstOrNull()
        }
    }

    private fun loadProfileByUuid(uuid: UUID): Profile? {
        return manager.executeTransaction {
            profileTable.selectAll().where { profileTable.uuid eq uuid }
                .limit(1)
                .map {
                    Profile(
                        id = it[profileTable.id],
                        name = it[profileTable.name],
                        uuid = it[profileTable.uuid]
                    )
                }
                .firstOrNull()
        }
    }

    private fun loadProfileByNameOrUuid(name: String, uuid: UUID): Profile? {
        return manager.executeTransaction {
            profileTable.selectAll().where {
                (profileTable.name eq name) or (profileTable.uuid eq uuid)
            }.limit(1)
                .map {
                    Profile(
                        id = it[profileTable.id],
                        name = it[profileTable.name],
                        uuid = it[profileTable.uuid]
                    )
                }
                .firstOrNull()
        }
    }
    
    /**
     * 根据 name 或 uuid 查找档案（OR 查询）
     * 
     * @param name 玩家名
     * @param uuid 玩家UUID
     * @return 档案ID，如果不存在返回 null
     */
    fun findProfileByNameOrUuid(name: String, uuid: UUID): UUID? {
        return getProfileByNameOrUuid(name, uuid)?.id
    }

    /**
     * 根据 name 或 uuid 获取档案（带缓存）
     *
     * @param name 玩家名
     * @param uuid 玩家UUID
     * @return 档案信息，如果不存在返回 null
     */
    fun getProfileByNameOrUuid(name: String, uuid: UUID): Profile? {
        profileCacheByName[name.lowercase()]?.let {
            warn { "Profile 命中 name/uuid 回退逻辑: name=$name uuid=$uuid" }
            return it
        }
        profileCacheByUuid[uuid]?.let {
            warn { "Profile 命中 name/uuid 回退逻辑: name=$name uuid=$uuid" }
            return it
        }

        val loaded = loadProfileByNameOrUuid(name, uuid) ?: return null
        warn { "Profile 命中 name/uuid 回退逻辑: name=$name uuid=$uuid" }
        cacheProfile(loaded)
        return loaded
    }

    fun getProfileByName(name: String): Profile? {
        profileCacheByName[name.lowercase()]?.let { return it }

        val loaded = loadProfileByName(name) ?: return null
        cacheProfile(loaded)
        return loaded
    }

    fun getProfileByUuid(uuid: UUID): Profile? {
        profileCacheByUuid[uuid]?.let { return it }

        val loaded = loadProfileByUuid(uuid) ?: return null
        cacheProfile(loaded)
        return loaded
    }

    /**
     * 创建新的游戏档案
     * 
     * @param id 档案ID
     * @param name 玩家名
     * @param uuid 玩家UUID
     * @return 是否创建成功
     */
    fun createProfile(id: UUID, name: String, uuid: UUID): Boolean {
        return try {
            manager.executeTransaction {
                profileTable.insert {
                    it[profileTable.id] = id
                    it[profileTable.name] = name
                    it[profileTable.uuid] = uuid
                }
            }
            cacheProfile(Profile(id = id, name = name, uuid = uuid))
            true
        } catch (e: Exception) {
            warn { "创建档案失败: ${e.message}" }
            false
        }
    }

    /**
     * 同步外部认证改名到正式 Profile。
     *
     * 仅用于玩家在 Yggdrasil / Mojang 等外部认证服务侧改名后，
     * 将认证返回的最新名称同步写入正式 Profile 表。
     *
     * - 若 Profile 不存在或名称本就一致，直接返回 true；
     * - 若新名称已被其他 Profile 占用，返回 false 并保留旧名，避免破坏登录；
     * - 成功更新后同步刷新三套缓存（id / name / uuid）。
     *
     * @param profileId 待同步的正式档案标识
     * @param newName 外部认证返回的最新名称
     * @return true 表示已同步或本就一致；false 表示同步失败（如名称冲突）
     */
    fun updateProfileName(profileId: UUID, newName: String): Boolean {
        val existing = getProfile(profileId) ?: return false
        if (existing.name == newName) {
            return true
        }

        val conflicting = getProfileByName(newName)
        if (conflicting != null && conflicting.id != profileId) {
            warn { "无法同步 Profile $profileId 名称 '$newName'：名称已被其他 Profile ${conflicting.id} 占用，保留旧名 '${existing.name}'" }
            return false
        }

        return try {
            manager.executeTransaction {
                profileTable.update({ profileTable.id eq profileId }) {
                    it[profileTable.name] = newName
                }
            }
            profileCacheByName.remove(existing.name.lowercase())
            val updated = existing.copy(name = newName)
            profileCacheById[profileId] = updated
            profileCacheByName[newName.lowercase()] = updated
            true
        } catch (e: Exception) {
            warn { "同步 Profile $profileId 名称失败: ${e.message}" }
            false
        }
    }

    fun validateTrustedProfileCreate(name: String, uuid: UUID): String? {
        val existingByUuid = getProfileByUuid(uuid)
        val existingByName = getProfileByName(name)

        if (existingByUuid != null && existingByName != null && existingByUuid.id != existingByName.id) {
            return "名称 $name 已被其他 Profile 占用，且 UUID 已映射到 ${existingByUuid.id}"
        }

        if (existingByUuid != null) {
            return "UUID $uuid 已映射到现有 Profile: ${existingByUuid.id}"
        }

        if (existingByName != null) {
            return if (existingByName.uuid == uuid) {
                "名称 $name 已映射到现有 Profile: ${existingByName.id}"
            } else {
                "名称 $name 已被其他 UUID 占用"
            }
        }

        return null
    }


    /**
     * 获取档案信息
     * 
     * @param profileId 档案ID
     * @return 档案信息，如果不存在返回 null
     */
    fun getProfile(profileId: UUID): Profile? {
        profileCacheById[profileId]?.let { return it }

        val loaded = loadProfileById(profileId) ?: return null
        cacheProfile(loaded)
        return loaded
    }
}
