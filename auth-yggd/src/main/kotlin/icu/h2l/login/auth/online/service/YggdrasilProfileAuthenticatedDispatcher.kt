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

import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.event.profile.ProfileAuthenticatedEvent
import icu.h2l.api.log.error
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.login.auth.online.config.YggdrasilDebug
import icu.h2l.login.auth.online.record.YggdrasilAuthResult

/**
 * Yggdrasil 认证完成后的事件分发器
 *
 * 负责在外部认证成功后触发 [ProfileAuthenticatedEvent]，
 * 通知各功能模块可以根据认证档案提前准备数据。
 *
 * 本类不关心档案中具体包含什么信息（皮肤、披风等），
 * 只负责传递完整的 [com.velocitypowered.api.util.GameProfile] 给监听器。
 */
class YggdrasilProfileAuthenticatedDispatcher(
    private val proxy: ProxyServer
) {
    fun fireProfileAuthenticatedEvent(
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult.Success
    ) {
        val event = ProfileAuthenticatedEvent(
            hyperZonePlayer = handler,
            authenticatedProfile = result.profile,
            entryId = result.entryId,
            serverUrl = result.serverUrl
        )

        YggdrasilDebug.log {
            "[ProfileAuthenticatedFlow] dispatch start: clientOriginal=${handler.clientOriginalName}, entry=${result.entryId}, server=${result.serverUrl}, profile=${describeProfile(result.profile)}"
        }

        runCatching {
            proxy.eventManager.fire(event).join()
        }.onSuccess {
            YggdrasilDebug.log {
                "[ProfileAuthenticatedFlow] dispatch completed: clientOriginal=${handler.clientOriginalName}, entry=${result.entryId}"
            }
        }.onFailure { throwable ->
            error(throwable) { "Profile authenticated event dispatch failed: ${throwable.message}" }
        }
    }

    private fun describeProfile(profile: com.velocitypowered.api.util.GameProfile): String {
        return "id=${profile.id}, name=${profile.name}, properties=${profile.properties.size}"
    }
}
