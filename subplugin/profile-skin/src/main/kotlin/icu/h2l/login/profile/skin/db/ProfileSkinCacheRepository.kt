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
import icu.h2l.login.profile.skin.data.ProfileSkinSource
import icu.h2l.login.profile.skin.data.ProfileSkinTextures
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

data class ProfileSkinCacheRecord(
    val skinId: UUID,
    val sourceHash: String?,
    val sourceCacheEligible: Boolean?,
    val skinUrl: String?,
    val skinModel: String?,
    val textures: ProfileSkinTextures,
    val updatedAt: Long
)

internal fun isEligibleForSourceCache(record: ProfileSkinCacheRecord): Boolean {
    return record.sourceCacheEligible != false
}

internal fun hasSourceChanged(
    existing: ProfileSkinCacheRecord,
    source: ProfileSkinSource?,
    sourceHash: String?
): Boolean {
    val newUrl = source?.skinUrl
    val newModel = source?.model

    if (newUrl.isNullOrBlank() && sourceHash.isNullOrBlank()) {
        return false
    }

    if (source != null && sourceHash != existing.sourceHash) {
        return true
    }

    if (!newUrl.isNullOrBlank() && newUrl != existing.skinUrl) {
        return true
    }

    if (!newModel.isNullOrBlank() && newModel != existing.skinModel) {
        return true
    }

    return false
}

internal fun hasTexturesChanged(
    existing: ProfileSkinCacheRecord,
    textures: ProfileSkinTextures
): Boolean {
    return existing.textures.value != textures.value || existing.textures.signature != textures.signature
}

internal fun hasSourceCacheEligibilityChanged(
    existing: ProfileSkinCacheRecord,
    sourceCacheEligible: Boolean
): Boolean {
    return existing.sourceCacheEligible != sourceCacheEligible
}

internal fun shouldSkipSave(
    existing: ProfileSkinCacheRecord?,
    source: ProfileSkinSource?,
    textures: ProfileSkinTextures,
    sourceHash: String?,
    sourceCacheEligible: Boolean
): Boolean {
    if (existing == null) {
        return false
    }
    return !hasSourceChanged(existing, source, sourceHash)
            && !hasTexturesChanged(existing, textures)
            && !hasSourceCacheEligibilityChanged(existing, sourceCacheEligible)
}

internal fun shouldUseSharedCacheEntry(sourceHash: String?, sourceCacheEligible: Boolean): Boolean {
    return sourceCacheEligible && !sourceHash.isNullOrBlank()
}

class ProfileSkinCacheRepository(
    private val databaseManager: HyperZoneDatabaseManager,
    private val cacheTable: ProfileSkinCacheTable
) {
    data class SaveResult(
        val action: Action,
        val record: ProfileSkinCacheRecord
    ) {
        enum class Action {
            INSERTED,
            UPDATED,
            SKIPPED
        }
    }

    fun findBySkinId(skinId: UUID): ProfileSkinCacheRecord? {
        return databaseManager.executeTransaction {
            cacheTable.selectAll().where { cacheTable.id eq skinId }
                .limit(1)
                .map(::toCacheRecord)
                .firstOrNull()
        }
    }

    fun findBySourceHash(sourceHash: String): ProfileSkinCacheRecord? {
        return databaseManager.executeTransaction {
            cacheTable.selectAll().where { cacheTable.sourceHash eq sourceHash }
                .map(::toCacheRecord)
                .filter(::isEligibleForSourceCache)
                .maxByOrNull(ProfileSkinCacheRecord::updatedAt)
        }
    }

    fun save(
        source: ProfileSkinSource?,
        textures: ProfileSkinTextures,
        sourceHash: String?,
        sourceCacheEligible: Boolean
    ): SaveResult {
        val existing = if (shouldUseSharedCacheEntry(sourceHash, sourceCacheEligible)) {
            databaseManager.executeTransaction {
                cacheTable.selectAll().where { cacheTable.sourceHash eq sourceHash }
                    .map(::toCacheRecord)
                    .maxByOrNull(ProfileSkinCacheRecord::updatedAt)
            }
        } else {
            null
        }
        if (shouldSkipSave(existing, source, textures, sourceHash, sourceCacheEligible)) {
            return SaveResult(SaveResult.Action.SKIPPED, existing!!)
        }

        if (existing != null) {
            return update(existing.skinId, source, textures, sourceHash, sourceCacheEligible)
        }

        return insert(UUID.randomUUID(), source, textures, sourceHash, sourceCacheEligible)
    }

    private fun insert(
        skinId: UUID,
        source: ProfileSkinSource?,
        textures: ProfileSkinTextures,
        sourceHash: String?,
        sourceCacheEligible: Boolean
    ): SaveResult {
        val now = System.currentTimeMillis()
        val record = ProfileSkinCacheRecord(
            skinId = skinId,
            sourceHash = sourceHash,
            sourceCacheEligible = sourceCacheEligible,
            skinUrl = source?.skinUrl,
            skinModel = source?.model,
            textures = textures,
            updatedAt = now
        )
        return try {
            databaseManager.executeTransaction {
                cacheTable.insert {
                    it[cacheTable.id] = skinId
                    it[cacheTable.sourceHash] = sourceHash
                    it[cacheTable.sourceCacheEligible] = sourceCacheEligible
                    it[cacheTable.skinUrl] = source?.skinUrl
                    it[cacheTable.skinModel] = source?.model
                    it[cacheTable.textureValue] = textures.value
                    it[cacheTable.textureSignature] = textures.signature
                    it[cacheTable.updatedAt] = now
                }
            }
            SaveResult(SaveResult.Action.INSERTED, record)
        } catch (e: Exception) {
            warn { "写入皮肤缓存失败: ${e.message}" }
            SaveResult(SaveResult.Action.SKIPPED, record)
        }
    }

    private fun update(
        skinId: UUID,
        source: ProfileSkinSource?,
        textures: ProfileSkinTextures,
        sourceHash: String?,
        sourceCacheEligible: Boolean
    ): SaveResult {
        val now = System.currentTimeMillis()
        val updated = try {
            databaseManager.executeTransaction {
                cacheTable.update({ cacheTable.id eq skinId }) {
                    it[cacheTable.sourceHash] = sourceHash
                    it[cacheTable.sourceCacheEligible] = sourceCacheEligible
                    it[cacheTable.skinUrl] = source?.skinUrl
                    it[cacheTable.skinModel] = source?.model
                    it[cacheTable.textureValue] = textures.value
                    it[cacheTable.textureSignature] = textures.signature
                    it[cacheTable.updatedAt] = now
                }
            } > 0
        } catch (e: Exception) {
            warn { "更新皮肤缓存失败: ${e.message}" }
            false
        }

        val record = ProfileSkinCacheRecord(
            skinId = skinId,
            sourceHash = sourceHash,
            sourceCacheEligible = sourceCacheEligible,
            skinUrl = source?.skinUrl,
            skinModel = source?.model,
            textures = textures,
            updatedAt = now
        )
        if (updated) {
            return SaveResult(SaveResult.Action.UPDATED, record)
        }

        return SaveResult(SaveResult.Action.SKIPPED, record)
    }

    private fun toCacheRecord(row: ResultRow): ProfileSkinCacheRecord {
        return ProfileSkinCacheRecord(
            skinId = row[cacheTable.id],
            sourceHash = row[cacheTable.sourceHash],
            sourceCacheEligible = row[cacheTable.sourceCacheEligible],
            skinUrl = row[cacheTable.skinUrl],
            skinModel = row[cacheTable.skinModel],
            textures = ProfileSkinTextures(
                value = row[cacheTable.textureValue],
                signature = row[cacheTable.textureSignature]
            ),
            updatedAt = row[cacheTable.updatedAt]
        )
    }
}

