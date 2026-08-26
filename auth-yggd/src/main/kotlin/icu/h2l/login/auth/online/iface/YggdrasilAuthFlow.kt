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

package icu.h2l.login.auth.online.iface

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.event.auth.LoginHandleResult
import icu.h2l.api.event.auth.LoginHandleSession
import icu.h2l.api.player.HyperZonePlayer
import io.netty.channel.Channel
import java.util.*
import java.util.concurrent.CompletableFuture

interface YggdrasilAuthFlow {
    fun startYggdrasilAuth(
        channel: Channel,
        username: String,
        uuid: UUID,
        serverId: String,
        playerIp: String? = null
    )

    fun registerWaitingAreaPlayer(player: Player, waitingAreaPlayer: HyperZonePlayer)

    fun requestWaitingAreaAuth(player: Player, session: LoginHandleSession): CompletableFuture<LoginHandleResult>

    fun clearPlayerCacheOnDisconnect(player: Player)
}
