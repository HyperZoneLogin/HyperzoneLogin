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

package icu.h2l.login.manager

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.proxy.Player
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.player.getChannel
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.player.VelocityHyperZonePlayer
import io.netty.channel.Channel
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object HyperZonePlayerManager : HyperZonePlayerAccessor {
    private val playersByPlayer = ConcurrentHashMap<Channel, VelocityHyperZonePlayer>()

    override fun create(channel: Channel, userName: String, uuid: UUID, isOnline: Boolean): HyperZonePlayer {
        val createdPlayer = VelocityHyperZonePlayer(userName, uuid, isOnline)
        val existing = playersByPlayer.putIfAbsent(channel, createdPlayer)
        if (existing != null) {
            throw IllegalStateException("重复创建 HyperZonePlayer：channel=$channel clientOriginal=$userName")
        }
        return createdPlayer
    }

    override fun getByPlayer(player: Player): HyperZonePlayer {
       return getByChannel(player.getChannel())
    }

    fun getByPlayerOrNull(player: Player): VelocityHyperZonePlayer? {
        return getByChannelOrNull(player.getChannel())
    }

    override fun getByChannel(channel: Channel): HyperZonePlayer {
        return playersByPlayer[channel]
            ?: throw IllegalStateException("未找到对应的 HyperZonePlayer：channel=$channel")
    }

    fun getByChannelOrNull(channel: Channel): VelocityHyperZonePlayer? {
        return playersByPlayer[channel]
    }

    fun remove(player: Player) {
        playersByPlayer.remove(player.getChannel())?.let { removedPlayer ->
            runCatching {
                HyperZoneLoginMain.getInstance().profileService.clear(removedPlayer)
            }
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        remove(player)
    }
}
