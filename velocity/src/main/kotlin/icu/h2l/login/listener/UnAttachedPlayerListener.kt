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

package icu.h2l.login.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import icu.h2l.api.HyperZoneApi
import icu.h2l.api.log.error
import icu.h2l.api.log.warn
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.message.MessageKeys

class UnAttachedPlayerListener(
    private val api: HyperZoneApi,
) {
    @Subscribe(priority = Short.MIN_VALUE)
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        val player = event.player
        try {
            val server = event.result.server.orElse(null) ?: return // null for denied.

            val hyperZonePlayer = api.hyperZonePlayers.getByPlayerOrNull(player)

            if (hyperZonePlayer == null) {
                denied(event, player)
                warn {
                    "Disconnected player ${player.username} from connecting '${server.serverInfo.name}': connection is not managed by HyperZoneLogin (requested '${server.serverInfo.name}')"
                }
                return
            }

            if (hyperZonePlayer.hasAttachedUser()) {
                return  // Authorized.
            }

            val waitingArea = api.getRegisteredVServer()
            if (waitingArea != null && server.serverInfo == waitingArea.serverInfo) {
                return  // Is joining waiting area.
            }

            val isFallback =
                event.previousServer != null || player.currentServer.isPresent // Lost connection from last server.
            if (!isFallback) {  // Initial connections.
                if (waitingArea != null) {
                    event.result = ServerPreConnectEvent.ServerResult.allowed(waitingArea)
                    warn {
                        "Redirect player ${player.username} to waiting area as a fallback"
                    }
                    return
                }

                if (api.serverAdapter?.isPlayerInWaitingArea(player) != true) {
                    denied(event, player)
                    warn {
                        "Disconnected player ${player.username} from connecting '${server.serverInfo.name}': no waiting area available"
                    }
                    return
                }
            }

            event.result = ServerPreConnectEvent.ServerResult.denied()
            warn {
                "Disconnected player ${player.username} from connecting '${server.serverInfo.name}': unauthorized"
            }
        } catch (ex: Throwable) {
            denied(event, player)
            error(ex) {
                "An unhandled error while authorizing player ${player.username}"
            }
        }
    }

    private fun denied(event: ServerPreConnectEvent, player: Player) {
        event.result = ServerPreConnectEvent.ServerResult.denied()
        val messages = HyperZoneLoginMain.getInstance().messageService
        player.disconnect(messages.render(player, MessageKeys.BackendAuth.MISCONFIGURED_DISCONNECT))
    }
}
