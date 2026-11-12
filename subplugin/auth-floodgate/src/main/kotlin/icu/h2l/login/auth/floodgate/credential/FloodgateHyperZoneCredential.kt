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

package icu.h2l.login.auth.floodgate.credential

import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.util.IPropertiesHolder
import icu.h2l.api.util.PropertiesHolder
import icu.h2l.login.auth.floodgate.db.FloodgateAuthRepository
import java.util.*

class FloodgateHyperZoneCredential(
    private val repository: FloodgateAuthRepository,
    private val trustedName: String,
    private val trustedUuid: UUID,
    private val xuid: Long,
    private val suggestedProfileCreateUuid: UUID?,
    private val knownUserUUID: UUID? = null,
) : HyperZoneCredential, IPropertiesHolder by PropertiesHolder() {
    override val channelId: String = CHANNEL_ID
    override val credentialId: String = trustedUuid.toString()

    override fun getRegistrationName(): String = trustedName

    override fun getBoundUserUUID(): UUID? {
        return knownUserUUID ?: repository.findProfileIdByXuid(xuid)
    }

    override fun getSuggestedGameUuid(): UUID? {
        return suggestedProfileCreateUuid
    }

    override fun validateBind(userUUID: UUID): String? {
        val currentUserUUID = getBoundUserUUID()
        if (currentUserUUID != null && userUUID != currentUserUUID) {
            return "Floodgate 凭证 $trustedName($xuid) 已绑定到其他 User: $currentUserUUID"
        }
        return null
    }

    override fun bind(userUUID: UUID): Boolean {
        val bound = repository.createOrUpdate(trustedName, xuid, userUUID)
        if (bound) {
            repository.updateGameUuid(xuid, trustedUuid)
        }
        return bound
    }

    /**
     * 只删除当前 XUID 与 User 的 Floodgate 绑定。
     *
     * 禁止在这里删除 HZL User、删除或切换 InGameProfile，或触发重新建档。
     */
    override fun unbind(userUUID: UUID): Boolean {
        return repository.deleteByXuid(xuid, userUUID)
    }

    fun matches(uuid: UUID): Boolean {
        return trustedUuid == uuid
    }

    /**
     * 创建一个新显示名称的凭证副本，用于 Rename 流程。
     *
     * Floodgate UUID 由 Mojang 分配，不受 rename 影响；
     * 仅更新显示给 User 服务的注册名。
     */
    fun withNewName(newName: String): FloodgateHyperZoneCredential {
        return FloodgateHyperZoneCredential(
            repository = repository,
            trustedName = newName,
            trustedUuid = trustedUuid,
            xuid = xuid,
            suggestedProfileCreateUuid = suggestedProfileCreateUuid,
            knownUserUUID = knownUserUUID,
        )
    }

    override fun withRegistrationName(newRegistrationName: String): FloodgateHyperZoneCredential {
        return withNewName(newRegistrationName)
    }

    companion object {
        private const val CHANNEL_ID = "floodgate"
    }
}

