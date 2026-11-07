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

package icu.h2l.login.profile

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.db.User
import icu.h2l.api.player.ClientLoginIdentity
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.login.message.MessageService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class UserUnbindServiceTest {
    private lateinit var userService: HyperZoneUserService
    private lateinit var messageService: MessageService
    private lateinit var service: UserUnbindService

    @BeforeEach
    fun setUp() {
        userService = mockk(relaxed = true)
        messageService = mockk(relaxed = true)
        service = UserUnbindService(userService, messageService)
    }

    @Test
    fun `unbind deletes current channel binding and disconnects player`() {
        val userUUID = UUID.randomUUID()
        val credential = FakeCredential()
        val proxyPlayer = mockk<Player>(relaxed = true)
        val player = FakePlayer(credential, proxyPlayer)
        every { userService.getAttachedUser(player) } returns User(userUUID)

        val result = service.unbind(player)

        assertTrue(result.success)
        assertEquals(userUUID, credential.unboundUserUUID)
        verify(exactly = 1) { proxyPlayer.disconnect(any()) }
        verify(exactly = 0) { userService.mergeUsers(any(), any()) }
        verify(exactly = 0) { userService.createUser(any<HyperZoneCredential>()) }
        verify(exactly = 0) { userService.attachUser(any(), any()) }
    }

    @Test
    fun `unbind rejects unsupported credential without disconnecting`() {
        val userUUID = UUID.randomUUID()
        val credential = FakeCredential(unsupported = true)
        val proxyPlayer = mockk<Player>(relaxed = true)
        val player = FakePlayer(credential, proxyPlayer)
        every { userService.getAttachedUser(player) } returns User(userUUID)

        val result = service.unbind(player)

        assertFalse(result.success)
        verify(exactly = 0) { proxyPlayer.disconnect(any()) }
        verify(exactly = 0) { userService.mergeUsers(any(), any()) }
        verify(exactly = 0) { userService.createUser(any<HyperZoneCredential>()) }
    }

    @Test
    fun `unbind does nothing when no user is attached`() {
        val credential = FakeCredential()
        val proxyPlayer = mockk<Player>(relaxed = true)
        val player = FakePlayer(credential, proxyPlayer)
        every { userService.getAttachedUser(player) } returns null

        val result = service.unbind(player)

        assertFalse(result.success)
        assertNull(credential.unboundUserUUID)
        verify(exactly = 0) { proxyPlayer.disconnect(any()) }
    }

    private class FakeCredential(
        private val unsupported: Boolean = false,
    ) : HyperZoneCredential {
        override val channelId: String = "test"
        override val credentialId: String = "credential"
        var unboundUserUUID: UUID? = null

        override fun getBoundUserUUID(): UUID? = null

        override fun bind(userUUID: UUID): Boolean = true

        override fun unbind(userUUID: UUID): Boolean {
            if (unsupported) {
                throw UnsupportedOperationException("unsupported")
            }
            unboundUserUUID = userUUID
            return true
        }
    }

    private class FakePlayer(
        private val credential: HyperZoneCredential,
        private val proxyPlayer: Player,
    ) : HyperZonePlayer {
        override val loginIdentity: ClientLoginIdentity = ClientLoginIdentity("UnbindPlayer", UUID.randomUUID())
        override val userUUID: UUID? = null
        override val isOnlinePlayer: Boolean = true

        override fun hasAttachedUser(): Boolean = true

        override fun getSubmittedCredentials(): List<HyperZoneCredential> = listOf(credential)

        override fun getProxyPlayerOrNull(): Player = proxyPlayer

        override fun sendMessage(message: Component) {
        }

        override fun getTemporaryGameProfile(): GameProfile = error("not used")

        override fun getAttachedGameProfile(): GameProfile = error("not used")
    }
}
