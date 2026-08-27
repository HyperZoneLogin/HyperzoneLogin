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

package icu.h2l.login.util

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import java.util.Locale

/**
 * 复用 Velocity 原生的初始服候选顺序：优先 forced-hosts，空时回退 try/attempt-connection-order。
 *
 * Velocity 自身的实际实现位于 `ConnectedPlayer#getNextServerToTry()`，但它是 private，
 * 对外没有可直接调用的公开 API；因此这里集中封装一份与其一致的解析逻辑，避免在业务代码里重复。
 */
internal fun resolveVelocityInitialTargetServerName(
    proxyServer: ProxyServer,
    player: Player,
    vararg excludedServerNames: String,
): String? {
    val config = proxyServer.configuration
    val hostKey = player.virtualHost
        .map { it.hostString.lowercase(Locale.ROOT) }
        .orElse("")

    val forcedOrder = config.forcedHosts[hostKey].orEmpty()
    val connectionOrder = if (forcedOrder.isNotEmpty()) forcedOrder else config.attemptConnectionOrder

    connectionOrder.firstOrNull { candidate ->
        candidate.isNotBlank() &&
            !excludedServerNames.any { excluded -> candidate.equals(excluded, ignoreCase = true) } &&
            proxyServer.getServer(candidate).isPresent
    }?.let { return it }

    return proxyServer.allServers
        .firstOrNull { candidate ->
            val candidateName = candidate.serverInfo.name
            candidateName.isNotBlank() &&
                !excludedServerNames.any { excluded -> candidateName.equals(excluded, ignoreCase = true) }
        }
        ?.serverInfo
        ?.name
}

