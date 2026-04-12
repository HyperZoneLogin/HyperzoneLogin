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

package icu.h2l.login.auth.online

import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import java.util.UUID

class YggdrasilHyperZoneCredential(
    private val entryDatabaseHelper: EntryDatabaseHelper,
    private val entryId: String,
    private val authenticatedName: String,
    private val authenticatedUUID: UUID,
    private val knownProfileId: UUID? = null
) : HyperZoneCredential {
    override val channelId: String = CHANNEL_ID
    override val credentialId: String = "$entryId:$authenticatedUUID"

    override fun getBoundProfileId(): UUID? {
        return knownProfileId ?: entryDatabaseHelper.findEntryByUuid(entryId, authenticatedUUID)
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

    companion object {
        private const val CHANNEL_ID = "yggdrasil"
    }
}

