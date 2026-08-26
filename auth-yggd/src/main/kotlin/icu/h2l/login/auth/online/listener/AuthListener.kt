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

package icu.h2l.login.auth.online.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.proxy.Player
import icu.h2l.api.event.auth.LoginHandleRequestEvent
import icu.h2l.api.event.connection.OpenStartAuthEvent
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.getChannel
import icu.h2l.login.auth.online.iface.YggdrasilAuthFlow
import io.netty.channel.Channel
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class AuthListener(
    private val yggdrasilAuthModule: YggdrasilAuthFlow
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
        yggdrasilAuthModule.startYggdrasilAuth(
            channel = event.channel,
            username = event.userName,
            uuid = event.userUUID,
            serverId = event.serverId,
            playerIp = event.playerIp
        )
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
            "[YggdrasilFlow] OnlineAuthEvent 收到，已提前启动验证，等待等待区进入后提交结果: addr=${event.channel}, user=${event.userName}"
        }
    }


    @Subscribe
    fun onLoginHandleRequest(event: LoginHandleRequestEvent) {
        if (!event.proxyPlayer.isOnlineMode) return
        if (event.hyperZonePlayer.hasAttachedProfile()) return

        event.claim("auth-yggd:waiting-area") { context ->
            val channel = context.proxyPlayer.getChannel()
            if (pendingContexts.remove(channel) == null) {
                return@claim CompletableFuture.completedFuture(
                    icu.h2l.api.event.auth.LoginHandleResult.failed("no pending yggdrasil context")
                )
            }
            val future = yggdrasilAuthModule.requestWaitingAreaAuth(context.proxyPlayer, context.session)
            context.cancelSignal.whenComplete { _, _ -> future.cancel(false) }
            future
        }
    }

    internal fun onWaitingAreaJoin(channel: Channel, proxyPlayer: Player, hyperZonePlayer: HyperZonePlayer) {
        if (!proxyPlayer.isOnlineMode) return
        if (hyperZonePlayer.hasAttachedProfile()) return

        val pending = pendingContexts.remove(channel)
        if (pending == null) {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilFlow] WaitingAreaJoin 未找到待验证上下文，跳过: addr=$channel" }
            return
        }

        val username = proxyPlayer.username
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilFlow] WaitingAreaJoin 收到，认证结果将在此阶段提交: user=$username" }
        yggdrasilAuthModule.registerWaitingAreaPlayer(proxyPlayer, hyperZonePlayer)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        onDisconnect(event.player.getChannel(), event.player)
    }

    internal fun onDisconnect(channel: Channel, player: Player) {
        pendingContexts.remove(channel)
        yggdrasilAuthModule.clearPlayerCacheOnDisconnect(player)
    }
}

private data class PendingAuthContext(
    val username: String,
    val uuid: UUID,
    val serverId: String,
    val playerIp: String?
)
