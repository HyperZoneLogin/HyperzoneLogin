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

package icu.h2l.login.auth.online.service

import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.db.InGameProfileCandidate
import icu.h2l.api.log.warn
import icu.h2l.login.auth.online.config.YggdrasilDebug
import icu.h2l.api.profile.CredentialChannelRegistryProvider
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.login.auth.online.config.EntryConfig
import icu.h2l.login.auth.online.config.YggdrasilConfigLoader
import icu.h2l.login.auth.online.credential.YggdrasilHyperZoneCredential
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import icu.h2l.login.auth.online.manager.EntryConfigManager
import icu.h2l.login.auth.online.message.YggdrasilMessages
import icu.h2l.login.auth.online.record.YggdrasilCredentialPreparation
import icu.h2l.login.auth.online.record.YggdrasilAuthResult
import java.util.*

class YggdrasilCredentialService(
    private val entryConfigManager: EntryConfigManager,
    private val userService: HyperZoneUserService,
    private val entryDatabaseHelper: EntryDatabaseHelper
) {
    fun prepareCredentialForSuccessfulAuth(
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult.Success
    ): YggdrasilCredentialPreparation {
        if (userService.getAttachedUser(handler) != null) {
            return YggdrasilCredentialPreparation(credentialToSubmit = null)
        }

        val existingBoundProfileId = findBoundProfileIdByAuthenticatedEntry(result)
        if (existingBoundProfileId != null) {
            return YggdrasilCredentialPreparation(
                credentialToSubmit = yggdrasilCredential(
                    entryId = result.entryId,
                    authenticatedName = result.profile.name,
                    authenticatedUuid = result.profile.id,
                    suggestedProfileCreateUuid = resolveProfileResolveUuid(result),
                    knownUserUUID = existingBoundProfileId
                ),
            )
        }

        val profileResolveUuid = resolveProfileResolveUuid(result)
        val probeCredential = yggdrasilCredential(
            entryId = result.entryId,
            authenticatedName = result.profile.name,
            authenticatedUuid = result.profile.id,
            suggestedProfileCreateUuid = profileResolveUuid
        )

        val channelAbility = CredentialChannelRegistryProvider.getOrNull()?.getChannelAbility("yggdrasil")
        if (channelAbility?.canRegister == false) {
            return YggdrasilCredentialPreparation(
                credentialToSubmit = probeCredential,
                failureReason = YggdrasilMessages.registrationDisabledReason(handler)
            )
        }

        if (userService.canCreateUser(probeCredential)) {
            val createdUser = try {
                userService.createUser(probeCredential)
            } catch (throwable: IllegalStateException) {
                return YggdrasilCredentialPreparation(
                    credentialToSubmit = null,
                    failureReason = throwable.message ?: "创建 User 失败"
                )
            }

            val bound = entryDatabaseHelper.createEntry(
                entryId = result.entryId,
                name = result.profile.name,
                uuid = result.profile.id,
                pid = createdUser.id
            )
            if (bound) {
                return YggdrasilCredentialPreparation(
                    credentialToSubmit = yggdrasilCredential(
                        entryId = result.entryId,
                        authenticatedName = result.profile.name,
                        authenticatedUuid = result.profile.id,
                        suggestedProfileCreateUuid = profileResolveUuid,
                        knownUserUUID = createdUser.id
                    ),
                )
            }
        }

        return YggdrasilCredentialPreparation(
            credentialToSubmit = yggdrasilCredential(
                entryId = result.entryId,
                authenticatedName = result.profile.name,
                authenticatedUuid = result.profile.id,
                suggestedProfileCreateUuid = profileResolveUuid
            )
        )
    }

    fun findBoundProfileIdByAuthenticatedEntry(success: YggdrasilAuthResult.Success): UUID? {
        val directUserUUID = entryDatabaseHelper.findEntryByUuid(
            entryId = success.entryId,
            uuid = success.profile.id
        )
        val linkedUserUUID = if (directUserUUID == null) {
            findLinkedProfileId(success)
        } else {
            null
        }

        if (linkedUserUUID != null) {
            val entryConfig = entryConfigManager.getConfigById(success.entryId)
            val resolvedUuid = if (entryConfig?.yggdrasil?.passYggdrasilUuidToProfileResolve == true) {
                success.profile.id
            } else {
                userService.defaultGameUuid(success.profile.name)
            }
            val candidate = InGameProfileCandidate(
                channel = "yggdrasil",
                name = success.profile.name,
                uuid = resolvedUuid,
                channelEntry = success.entryId,
                channelAccountId = success.profile.id.toString(),
            )
            userService.validateInGameProfileCandidate(linkedUserUUID, candidate)?.let { reason ->
                warn {
                    "Yggdrasil 联动入口 ${success.entryId} 自动绑定到 User $linkedUserUUID 被拒绝: $reason"
                }
                return null
            }

            val bound = entryDatabaseHelper.createEntry(
                entryId = success.entryId,
                name = success.profile.name,
                uuid = success.profile.id,
                pid = linkedUserUUID,
            )
            if (!bound) {
                warn { "Yggdrasil 联动入口 ${success.entryId} 自动绑定失败: uuid=${success.profile.id} pid=$linkedUserUUID" }
                return null
            }
            YggdrasilDebug.log {
                "[YggdrasilFlow] 入口 ${success.entryId} 通过联动组自动绑定到 User $linkedUserUUID"
            }
        }

        val userUUID = directUserUUID ?: linkedUserUUID ?: return null

        entryDatabaseHelper.updateEntryName(
            entryId = success.entryId,
            uuid = success.profile.id,
            newName = success.profile.name
        )

        return userUUID
    }

    /**
     * 在配置的联动组内查找同 UUID 的其他入口绑定。
     *
     * 只有组内所有命中记录都指向同一个 User 时才自动联动；
     * 指向多个 User 时拒绝自动绑定，交由玩家走手动绑定流程。
     */
    private fun findLinkedProfileId(success: YggdrasilAuthResult.Success): UUID? {
        val currentEntry = success.entryId.lowercase()
        val groups = YggdrasilConfigLoader.getConfig().linkGroups.values
        val group = groups.firstOrNull { entries ->
            entries.any { it.equals(currentEntry, ignoreCase = true) }
        } ?: return null

        val linkedEntries = group.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != currentEntry }
            .distinct()
            .toList()
        if (linkedEntries.isEmpty()) {
            return null
        }

        val userUUIDs = entryDatabaseHelper.findProfileIdsByUuid(linkedEntries, success.profile.id)
        if (userUUIDs.size > 1) {
            warn {
                "Yggdrasil 联动组内 UUID ${success.profile.id} 同时绑定多个 User: $userUUIDs，跳过自动绑定"
            }
            return null
        }
        return userUUIDs.singleOrNull()
    }

    private fun resolveProfileResolveUuid(result: YggdrasilAuthResult.Success): UUID? {
        val entryConfig = entryConfigManager.getConfigById(result.entryId)
        if (entryConfig == null) {
            YggdrasilDebug.log {
                "[YggdrasilFlow] 未找到 Entry ${result.entryId} 的配置，User 解析回退为透传远端 UUID: ${result.profile.id}"
            }
            return result.profile.id
        }

        return if (entryConfig.yggdrasil.passYggdrasilUuidToProfileResolve) {
            result.profile.id
        } else {
            null
        }
    }

    private fun yggdrasilCredential(
        entryId: String,
        authenticatedName: String,
        authenticatedUuid: UUID,
        suggestedProfileCreateUuid: UUID?,
        knownUserUUID: UUID? = null
    ): YggdrasilHyperZoneCredential {
        return YggdrasilHyperZoneCredential(
            entryDatabaseHelper = entryDatabaseHelper,
            entryId = entryId,
            authenticatedName = authenticatedName,
            authenticatedUUID = authenticatedUuid,
            suggestedProfileCreateUuid = suggestedProfileCreateUuid,
            knownUserUUID = knownUserUUID
        )
    }
}
