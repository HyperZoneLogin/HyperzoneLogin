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

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import icu.h2l.api.event.connection.OpenStartAuthEvent
import icu.h2l.api.event.vServer.VServerJoinEvent
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.player.getChannel
import io.netty.channel.Channel
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class YggdrasilEventListener(
    private val yggdrasilAuthModule: YggdrasilAuthModule
) {
    private val pendingContexts = ConcurrentHashMap<Channel, PendingAuthContext>()

    @Subscribe
    fun onOnlineAuth(event: OpenStartAuthEvent) {
        if (!event.isOnline) return

        pendingContexts[event.channel] = PendingAuthContext(
            username = event.userName,
            uuid = event.userUUID,
            serverId = event.serverId,
            playerIp = event.playerIp
        )
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
            "[YggdrasilFlow] OnlineAuthEvent 收到，等待等待区进入事件触发验证: addr=${event.channel}, user=${event.userName}"
        }
    }

    @Subscribe
    fun onWaitingAreaJoin(event: VServerJoinEvent) {
        if (!event.proxyPlayer.isOnlineMode) return
        if (!event.hyperZonePlayer.isInWaitingArea()) return

        val channel = event.proxyPlayer.getChannel()
        val pending = pendingContexts.remove(channel)
        if (pending == null) {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilFlow] WaitingAreaJoin 未找到待验证上下文，跳过: addr=$channel" }
            return
        }

        val username = event.proxyPlayer.username
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilFlow] WaitingAreaJoin 收到，开始验证: user=$username" }
        yggdrasilAuthModule.startYggdrasilAuth(
            player = event.proxyPlayer,
            username = pending.username,
            uuid = pending.uuid,
            serverId = pending.serverId,
            playerIp = pending.playerIp
        )
        yggdrasilAuthModule.registerWaitingAreaPlayer(event.proxyPlayer, event.hyperZonePlayer)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        pendingContexts.remove(event.player.getChannel())
        yggdrasilAuthModule.clearPlayerCacheOnDisconnect(event.player)
    }
}

private data class PendingAuthContext(
    val username: String,
    val uuid: UUID,
    val serverId: String,
    val playerIp: String?
)
