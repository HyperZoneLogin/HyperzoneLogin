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

package icu.h2l.login.auth.online.credential

import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import java.util.*

class YggdrasilHyperZoneCredential(
    private val entryDatabaseHelper: EntryDatabaseHelper,
    private val entryId: String,
    private val authenticatedName: String,
    private val authenticatedUUID: UUID,
    private val suggestedProfileCreateUuid: UUID?,
    private val knownProfileId: UUID? = null
) : HyperZoneCredential {
    override val channelId: String = CHANNEL_ID
    override val credentialId: String = "$entryId:$authenticatedUUID"

    override fun getRegistrationName(): String = authenticatedName

    override fun getBoundProfileId(): UUID? {
        return knownProfileId ?: entryDatabaseHelper.findEntryByUuid(entryId, authenticatedUUID)
    }

    override fun getSuggestedProfileCreateUuid(): UUID? {
        return suggestedProfileCreateUuid
    }

    override fun validateBind(profileId: UUID): String? {
        val currentProfileId = getBoundProfileId()
        if (currentProfileId != null && currentProfileId != profileId) {
            return "Yggdrasil 凭证 $credentialId 已绑定到其他 Profile: $currentProfileId"
        }
        return null
    }

    override fun bind(profileId: UUID): Boolean {
        return entryDatabaseHelper.createEntry(
            entryId = entryId,
            name = authenticatedName,
            uuid = authenticatedUUID,
            pid = profileId
        )
    }

    /**
     * 创建一个更新了建议 UUID 的凭证副本，用于 ReUuid 流程。
     *
     * Yggdrasil 认证名与 UUID 由 Mojang 认证，不受 rename 影响；
     * 仅更新向 Profile 服务建议的建档 UUID（传入 null 表示由核心 ReUuid 逻辑自行决定）。
     */
    fun withNewSuggestedUuid(newSuggestedUuid: UUID?): YggdrasilHyperZoneCredential {
        return YggdrasilHyperZoneCredential(
            entryDatabaseHelper = entryDatabaseHelper,
            entryId = entryId,
            authenticatedName = authenticatedName,
            authenticatedUUID = authenticatedUUID,
            suggestedProfileCreateUuid = newSuggestedUuid,
            knownProfileId = knownProfileId
        )
    }

    override fun withReUuidRequest(): YggdrasilHyperZoneCredential {
        return withNewSuggestedUuid(null)
    }

    companion object {
        private const val CHANNEL_ID = "yggdrasil"
    }
}
