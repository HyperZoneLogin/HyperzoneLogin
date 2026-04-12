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

package icu.h2l.login.player

import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.protocol.packet.LegacyPlayerListItemPacket
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket
import icu.h2l.api.log.debug
import java.util.EnumSet

/**
 * 负责向客户端直接补发 self `ADD_PLAYER`。
 */
internal object SelfPlayerInfoSkinSender {
	fun sendAddPlayer(player: ConnectedPlayer, profile: GameProfile) {
		val protocolVersion = player.protocolVersion
		if (protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
			player.connection.write(createModernAddPlayer(profile))
			return
		}

		if (protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_8)) {
			player.connection.write(createLegacyAddPlayer(profile))
			return
		}

		debug {
			"[ProfileSkinFlow] self ADD_PLAYER skipped: unsupported protocol for skin properties, player=${player.username}, protocol=$protocolVersion"
		}
	}

	private fun createModernAddPlayer(profile: GameProfile): UpsertPlayerInfoPacket {
		val entry = UpsertPlayerInfoPacket.Entry(profile.id)
		entry.setProfile(profile)
		entry.setLatency(0)
		entry.setListed(true)
		return UpsertPlayerInfoPacket(
			EnumSet.of(
				UpsertPlayerInfoPacket.Action.ADD_PLAYER,
				UpsertPlayerInfoPacket.Action.UPDATE_LATENCY,
				UpsertPlayerInfoPacket.Action.UPDATE_LISTED
			),
			listOf(entry)
		)
	}

	private fun createLegacyAddPlayer(profile: GameProfile): LegacyPlayerListItemPacket {
		val item = LegacyPlayerListItemPacket.Item(profile.id)
			.setName(profile.name)
			.setProperties(profile.properties)
			.setGameMode(0)
			.setLatency(0)
		return LegacyPlayerListItemPacket(LegacyPlayerListItemPacket.ADD_PLAYER, listOf(item))
	}
}


