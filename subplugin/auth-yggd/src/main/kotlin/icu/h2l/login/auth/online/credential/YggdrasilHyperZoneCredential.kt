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
import icu.h2l.api.util.IPropertiesHolder
import icu.h2l.api.util.PropertiesHolder
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import icu.h2l.login.auth.online.util.ChannelIdUtil
import java.util.*

class YggdrasilHyperZoneCredential(
    private val entryDatabaseHelper: EntryDatabaseHelper,
    private val entryId: String,
    private val authenticatedName: String,
    private val authenticatedUUID: UUID,
    private val suggestedProfileCreateUuid: UUID?,
    private val knownUserUUID: UUID? = null,
) : HyperZoneCredential, IPropertiesHolder by PropertiesHolder() {
    override val channelId: String = ChannelIdUtil.getChannelId(entryId)
    override val credentialId: String = "$entryId:$authenticatedUUID"

    init {
        setProperty(YGGDRASIL_ENTRY_ID_PROPERTY, entryId)
    }

    override fun getRegistrationName(): String = authenticatedName

    override fun getBoundUserUUID(): UUID? {
        return knownUserUUID ?: entryDatabaseHelper.findEntryByUuid(entryId, authenticatedUUID)
    }

    override fun getSuggestedGameUuid(): UUID? {
        return suggestedProfileCreateUuid
    }

    override fun validateBind(userUUID: UUID): String? {
        val currentUserUUID = getBoundUserUUID()
        if (currentUserUUID != null && currentUserUUID != userUUID) {
            return "Yggdrasil 凭证 $credentialId 已绑定到其他 User: $currentUserUUID"
        }
        return null
    }

    override fun bind(userUUID: UUID): Boolean {
        return entryDatabaseHelper.createEntry(
            entryId = entryId,
            name = authenticatedName,
            uuid = authenticatedUUID,
            pid = userUUID
        )
    }

    /**
     * 只删除当前入口 + UUID + User 完全匹配的 Yggdrasil 绑定。
     *
     * 禁止在这里删除 HZL User、删除或切换 InGameProfile，或触发重新建档。
     */
    override fun unbind(userUUID: UUID): Boolean {
        return entryDatabaseHelper.deleteEntry(entryId, authenticatedUUID, userUUID)
    }

    companion object {
        public const val YGGDRASIL_ENTRY_ID_PROPERTY = "yggdrasil-entry-id"
    }
}
