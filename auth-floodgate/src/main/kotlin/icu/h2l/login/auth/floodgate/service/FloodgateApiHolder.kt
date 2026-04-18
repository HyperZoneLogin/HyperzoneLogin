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

package icu.h2l.login.auth.floodgate.service

import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.floodgate.api.player.FloodgatePlayer
import org.geysermc.floodgate.api.player.PropertyKey
import java.net.InetSocketAddress
import java.util.UUID

open class FloodgateApiHolder(
    private val api: FloodgateApi = FloodgateApi.getInstance()
) {
    data class ResolvedFloodgateIdentity(
        val userName: String,
        val userUUID: UUID,
        val xuid: Long,
    )

    open fun isFloodgatePlayer(uuid: UUID): Boolean {
        return api.isFloodgatePlayer(uuid)
    }

    open fun getPlayerPrefix(): String {
        return api.playerPrefix
    }

    open fun resolveLoginIdentity(userName: String, playerIp: String): ResolvedFloodgateIdentity? {
        val loginName = userName.lowercase()
        val matchedPlayers = api.players.asSequence()
            .filter { player -> loginName in candidateNames(player) }
            .toList()
        if (matchedPlayers.isEmpty()) {
            return null
        }

        val socketMatchedPlayer = matchedPlayers.singleOrNull { player ->
            player.socketAddressHost()?.equals(playerIp, ignoreCase = true) == true
        }
        val resolvedPlayer = socketMatchedPlayer ?: matchedPlayers.singleOrNull() ?: return null
        return ResolvedFloodgateIdentity(
            userName = resolvedPlayer.correctUsername,
            userUUID = resolvedPlayer.correctUniqueId,
            xuid = resolvedPlayer.xuid?.toLongOrNull() ?: 0L,
        )
    }

    private fun candidateNames(player: FloodgatePlayer): Set<String> {
        return buildSet {
            add(player.username.lowercase())
            add(player.javaUsername.lowercase())
            add(player.correctUsername.lowercase())

            val prefix = getPlayerPrefix()
            if (prefix.isNotBlank()) {
                add(player.javaUsername.removePrefix(prefix).lowercase())
                add(player.correctUsername.removePrefix(prefix).lowercase())
            }
        }
    }

    private fun FloodgatePlayer.socketAddressHost(): String? {
        if (!hasProperty(PropertyKey.SOCKET_ADDRESS)) {
            return null
        }

        val address = runCatching {
            getProperty<Any?>(PropertyKey.SOCKET_ADDRESS)
        }.getOrNull() as? InetSocketAddress ?: return null
        return address.address?.hostAddress ?: address.hostString
    }
}

