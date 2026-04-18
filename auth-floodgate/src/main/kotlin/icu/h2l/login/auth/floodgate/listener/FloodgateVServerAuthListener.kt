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

package icu.h2l.login.auth.floodgate.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import icu.h2l.api.event.vServer.VServerAuthStartEvent
import icu.h2l.api.player.getChannel
import icu.h2l.login.auth.floodgate.FloodgateMessages
import icu.h2l.login.auth.floodgate.service.FloodgateAuthService

class FloodgateVServerAuthListener(
    private val authService: FloodgateAuthService
) {
    @Subscribe(priority = Short.MAX_VALUE)
    fun onVServerAuthStart(event: VServerAuthStartEvent) {
        val result = authService.complete(event.proxyPlayer.getChannel(), event.hyperZonePlayer)
        if (!result.handled) {
            return
        }
        if (!result.passed) {
            if (result.disconnectOnFailure) {
                event.proxyPlayer.disconnect(result.userMessage ?: FloodgateMessages.genericDisconnect())
            } else {
                result.userMessage?.let {
                    event.hyperZonePlayer.sendMessage(it)
                }
            }
            return
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        authService.clear(event.player.getChannel())
    }
}


