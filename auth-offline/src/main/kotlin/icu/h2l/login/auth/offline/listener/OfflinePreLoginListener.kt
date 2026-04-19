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

package icu.h2l.login.auth.offline.listener

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.event.connection.OpenPreLoginEvent
// HyperZonePlayerManager.create(...) belongs to core pre-login initialization and is intentionally
// kept in the core `velocity` EventListener. Do not initialize channel here to avoid ordering issues.
import icu.h2l.login.auth.offline.util.ExtraUuidUtils
import icu.h2l.login.auth.offline.type.OfflineUUIDType
import icu.h2l.login.auth.offline.config.OfflineMatchConfigLoader
import icu.h2l.api.log.info

class OfflinePreLoginListener {
    @Subscribe
    fun onPreLogin(event: OpenPreLoginEvent) {
        val uuid = event.uuid
        val name = event.userName
        val host = event.host
        // channel/player initialization is performed by the main plugin's EventListener

        val cfg = OfflineMatchConfigLoader.getConfig()
        if (!cfg.enable) return

        val offlineHost = cfg.hostMatch.start.any { it.startsWith(host) }
        if (offlineHost) {
            info { "匹配到离线 host 玩家: $name" }
        }
        val offlineUUIDType = ExtraUuidUtils.matchType(uuid, name)

        event.isOnline = !(offlineUUIDType != OfflineUUIDType.UNKNOWN || offlineHost)
        info { "传入 UUID 信息玩家: $name UUID:$uuid 类型: $offlineUUIDType 在线:${event.isOnline}" }
    }
}


