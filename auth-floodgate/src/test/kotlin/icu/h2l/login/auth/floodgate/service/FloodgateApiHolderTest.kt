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

import io.mockk.every
import io.mockk.mockk
import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.floodgate.api.player.FloodgatePlayer
import org.geysermc.floodgate.api.player.PropertyKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

class FloodgateApiHolderTest {
    @Test
    fun `resolveLoginIdentity matches java login name and socket address`() {
        val api = mockk<FloodgateApi>()
        val player = floodgatePlayer(
            username = "ktese",
            javaUsername = ".ktese",
            correctUsername = ".ktese",
            correctUniqueId = UUID.fromString("00000000-0000-0000-0009-01fb2b9f0a50"),
            xuid = "1234567890",
            socketAddress = InetSocketAddress("192.168.9.203", 19132),
        )
        every { api.players } returns listOf(player)
        every { api.playerPrefix } returns "."

        val holder = FloodgateApiHolder(api)
        val resolved = holder.resolveLoginIdentity("ktese", "192.168.9.203")

        assertEquals(".ktese", resolved?.userName)
        assertEquals(UUID.fromString("00000000-0000-0000-0009-01fb2b9f0a50"), resolved?.userUUID)
        assertEquals("1234567890".toLong(), resolved?.xuid)
    }

    @Test
    fun `resolveLoginIdentity returns null when multiple players share same login name without ip match`() {
        val api = mockk<FloodgateApi>()
        val first = floodgatePlayer(
            username = "ktese",
            javaUsername = ".ktese",
            correctUsername = ".ktese",
            correctUniqueId = UUID.fromString("00000000-0000-0000-0009-01fb2b9f0a50"),
            xuid = "1234567890",
            socketAddress = InetSocketAddress("192.168.9.201", 19132),
        )
        val second = floodgatePlayer(
            username = "ktese",
            javaUsername = ".ktese",
            correctUsername = ".ktese",
            correctUniqueId = UUID.fromString("00000000-0000-0000-0009-01fb2b9f0a51"),
            xuid = "0987654321",
            socketAddress = InetSocketAddress("192.168.9.202", 19132),
        )
        every { api.players } returns listOf(first, second)
        every { api.playerPrefix } returns "."

        val holder = FloodgateApiHolder(api)
        val resolved = holder.resolveLoginIdentity("ktese", "192.168.9.203")

        assertNull(resolved)
    }

    private fun floodgatePlayer(
        username: String,
        javaUsername: String,
        correctUsername: String,
        correctUniqueId: UUID,
        xuid: String,
        socketAddress: InetSocketAddress,
    ): FloodgatePlayer {
        val player = mockk<FloodgatePlayer>()
        every { player.username } returns username
        every { player.javaUsername } returns javaUsername
        every { player.correctUsername } returns correctUsername
        every { player.correctUniqueId } returns correctUniqueId
        every { player.xuid } returns xuid
        every { player.hasProperty(PropertyKey.SOCKET_ADDRESS) } returns true
        every { player.getProperty<Any?>(PropertyKey.SOCKET_ADDRESS) } returns socketAddress
        return player
    }
}

