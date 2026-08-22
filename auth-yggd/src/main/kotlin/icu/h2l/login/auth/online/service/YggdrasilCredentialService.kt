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

import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.CredentialChannelRegistryProvider
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.login.auth.online.config.EntryConfig
import icu.h2l.login.auth.online.credential.YggdrasilHyperZoneCredential
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import icu.h2l.login.auth.online.manager.EntryConfigManager
import icu.h2l.login.auth.online.message.YggdrasilMessages
import icu.h2l.login.auth.online.record.YggdrasilCredentialPreparation
import icu.h2l.login.auth.online.record.YggdrasilAuthResult
import java.util.*

class YggdrasilCredentialService(
    private val entryConfigManager: EntryConfigManager,
    private val profileService: HyperZoneProfileService,
    private val entryDatabaseHelper: EntryDatabaseHelper
) {
    fun prepareCredentialForSuccessfulAuth(
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult.Success
    ): YggdrasilCredentialPreparation {
        if (profileService.getAttachedProfile(handler) != null) {
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
                    knownProfileId = existingBoundProfileId
                )
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

        if (profileService.canCreate(probeCredential)) {
            val createdProfile = try {
                profileService.create(probeCredential)
            } catch (throwable: IllegalStateException) {
                return YggdrasilCredentialPreparation(
                    credentialToSubmit = null,
                    failureReason = throwable.message ?: "创建 Profile 失败"
                )
            }

            val bound = entryDatabaseHelper.createEntry(
                entryId = result.entryId,
                name = result.profile.name,
                uuid = result.profile.id,
                pid = createdProfile.id
            )
            if (bound) {
                return YggdrasilCredentialPreparation(
                    credentialToSubmit = yggdrasilCredential(
                        entryId = result.entryId,
                        authenticatedName = result.profile.name,
                        authenticatedUuid = result.profile.id,
                        suggestedProfileCreateUuid = profileResolveUuid,
                        knownProfileId = createdProfile.id
                    )
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
        val profileId = entryDatabaseHelper.findEntryByUuid(
            entryId = success.entryId,
            uuid = success.profile.id
        ) ?: return null

        entryDatabaseHelper.updateEntryName(
            entryId = success.entryId,
            uuid = success.profile.id,
            newName = success.profile.name
        )

        // 同步外部认证改名到正式 Profile（LS / Mojang 侧改名后，Profile 表也需更新）
        if (!profileService.syncProfileName(profileId, success.profile.name)) {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                "[YggdrasilFlow] Profile $profileId 名称同步到 '${success.profile.name}' 失败（可能名称冲突），保留旧名"
            }
        }

        return profileId
    }

    private fun resolveProfileResolveUuid(result: YggdrasilAuthResult.Success): UUID? {
        val entryConfig = entryConfigManager.getConfigById(result.entryId)
        if (entryConfig == null) {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                "[YggdrasilFlow] 未找到 Entry ${result.entryId} 的配置，Profile 解析回退为透传远端 UUID: ${result.profile.id}"
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
        knownProfileId: UUID? = null
    ): YggdrasilHyperZoneCredential {
        return YggdrasilHyperZoneCredential(
            entryDatabaseHelper = entryDatabaseHelper,
            entryId = entryId,
            authenticatedName = authenticatedName,
            authenticatedUUID = authenticatedUuid,
            suggestedProfileCreateUuid = suggestedProfileCreateUuid,
            knownProfileId = knownProfileId
        )
    }
}
