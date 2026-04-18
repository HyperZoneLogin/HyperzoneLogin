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

package icu.h2l.login.vServer.outpre

import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.proxy.protocol.MinecraftPacket
import com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutPreBackendBridgeSessionHandlerTest {
    @Test
    fun `outpre backend bridge remains compatible with server connection api`() {
        assertTrue(ServerConnection::class.java.isAssignableFrom(OutPreBackendBridge::class.java))
    }

    @Test
    fun `outpre backend bridge drops player info packets directly`() {
        assertTrue(shouldDropOutPreBackendPacket(mockk<UpsertPlayerInfoPacket>(relaxed = true)))
    }

    @Test
    fun `outpre backend bridge keeps non player info packets flowing`() {
        assertFalse(shouldDropOutPreBackendPacket(mockk<AvailableCommandsPacket>(relaxed = true)))
        assertFalse(shouldDropOutPreBackendPacket(mockk<MinecraftPacket>(relaxed = true)))
    }

    @Test
    fun `fast pass release consumes finished update even before backend config completes`() {
        assertTrue(
            shouldConsumeFinishedUpdateForVelocityRelease(
                releaseInProgress = true,
                configMode = true,
                bridgeConnected = false,
            )
        )
    }

    @Test
    fun `bridge config ack is preserved while release is not armed`() {
        assertFalse(
            shouldConsumeFinishedUpdateForVelocityRelease(
                releaseInProgress = false,
                configMode = true,
                bridgeConnected = true,
            )
        )
    }

    @Test
    fun `post config release still consumes finished update normally`() {
        assertTrue(
            shouldConsumeFinishedUpdateForVelocityRelease(
                releaseInProgress = true,
                configMode = false,
                bridgeConnected = false,
            )
        )
    }

    @Test
    fun `fast pass release switches directly into velocity config when bridge is absent`() {
        assertTrue(
            shouldReleaseDirectlyToVelocityConfig(
                configMode = true,
                bridgeConnected = false,
            )
        )
    }

    @Test
    fun `waiting area play release does not use direct config handoff`() {
        assertFalse(
            shouldReleaseDirectlyToVelocityConfig(
                configMode = false,
                bridgeConnected = false,
            )
        )
    }
}
