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
 * 数据库操作示例和帮助类
 */
class DatabaseHelper(
    private val manager: DatabaseManager
) {
    data class ProfileResolutionResult(
        val profile: Profile? = null,
        val created: Boolean = false,
        val reason: String? = null
    )


    private val profileTable = manager.getProfileTable()

    private val profileCacheById = ConcurrentHashMap<UUID, Profile>()
    private val profileCacheByName = ConcurrentHashMap<String, Profile>()
    private val profileCacheByUuid = ConcurrentHashMap<UUID, Profile>()

    private fun cacheProfile(profile: Profile) {
        profileCacheById[profile.id] = profile
        profileCacheByName[profile.name.lowercase()] = profile
        profileCacheByUuid[profile.uuid] = profile
    }

    private fun removeProfileCache(profile: Profile) {
        profileCacheById.remove(profile.id)
        profileCacheByName.remove(profile.name.lowercase())
        profileCacheByUuid.remove(profile.uuid)
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

    fun resolveTrustedProfile(name: String, uuid: UUID): ProfileResolutionResult {
        val existingByUuid = getProfileByUuid(uuid)
        val existingByName = getProfileByName(name)

        if (existingByUuid != null && existingByName != null && existingByUuid.id != existingByName.id) {
            return ProfileResolutionResult(
                reason = "名称 $name 已被其他 Profile 占用，且 UUID 已映射到 ${existingByUuid.id}"
            )
        }

        if (existingByUuid != null) {
            if (existingByUuid.name != name) {
                val updated = updateProfileName(existingByUuid.id, name)
                if (!updated) {
                    return ProfileResolutionResult(reason = "更新 Profile 名称失败")
                }
            }
            return ProfileResolutionResult(profile = getProfile(existingByUuid.id) ?: existingByUuid)
        }

        if (existingByName != null) {
            return if (existingByName.uuid == uuid) {
                ProfileResolutionResult(profile = existingByName)
            } else {
                ProfileResolutionResult(reason = "名称 $name 已被其他 UUID 占用")
            }
        }

        return ProfileResolutionResult()
    }

    fun resolveOrCreateTrustedProfile(name: String, uuid: UUID): ProfileResolutionResult {
        val resolved = resolveTrustedProfile(name, uuid)
        if (resolved.profile != null || resolved.reason != null) {
            return resolved
        }

        repeat(3) {
            val profileId = UUID.randomUUID()
            if (!createProfile(profileId, name, uuid)) {
                return@repeat
            }

            return ProfileResolutionResult(
                profile = getProfile(profileId) ?: Profile(profileId, name, uuid),
                created = true
            )
        }

        return ProfileResolutionResult(reason = "创建 Profile 失败")
    }

    /**
     * 更新档案名称
     * 
     * @param profileId 档案ID
     * @param newName 新名称
     * @return 是否更新成功
     */
    fun updateProfileName(profileId: UUID, newName: String): Boolean {
        return try {
            val updated = manager.executeTransaction {
                profileTable.update({ profileTable.id eq profileId }) {
                    it[name] = newName
                }
            } > 0

            if (!updated) {
                return false
            }

            val oldCached = profileCacheById[profileId]
            if (oldCached != null) {
                removeProfileCache(oldCached)
            }

            loadProfileById(profileId)?.let { cacheProfile(it) }

            true
        } catch (e: Exception) {
            warn { "更新档案名称失败: ${e.message}" }
            false
        }
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
