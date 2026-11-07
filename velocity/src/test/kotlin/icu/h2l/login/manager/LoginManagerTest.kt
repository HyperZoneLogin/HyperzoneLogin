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

import com.velocitypowered.api.event.EventManager
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.db.InGameProfile
import icu.h2l.api.db.InGameProfileCandidate
import icu.h2l.api.db.User
import icu.h2l.api.event.auth.LoginHandleRequestEvent
import icu.h2l.api.event.auth.LoginHandleResult
import icu.h2l.api.player.getChannel
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.api.profile.HyperZoneUserServiceProvider
import icu.h2l.login.player.VelocityHyperZonePlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class LoginManagerTest {
    @Test
    fun `dispatch returns no claims when no module applies`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val userService = mockk<HyperZoneUserService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>(relaxed = true)
        val channel = EmbeddedChannel()
        every { proxyServer.eventManager } returns eventManager
        HyperZoneUserServiceProvider.bind(userService)
        mockkStatic("icu.h2l.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedUser() } returns false
        every { eventManager.fire(any<Any>()) } answers {
            CompletableFuture.completedFuture(firstArg())
        }

        val result = LoginManager(proxyServer, userService, timeoutMillis = 200).dispatch(player, hyperPlayer).join()

        assertEquals(LoginManager.Status.NO_CLAIMS, result.status)
    }

    @Test
    fun `dispatch waits all claim failures`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val userService = mockk<HyperZoneUserService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>(relaxed = true)
        val channel = EmbeddedChannel()
        every { proxyServer.eventManager } returns eventManager
        HyperZoneUserServiceProvider.bind(userService)
        mockkStatic("icu.h2l.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedUser() } returns false
        every { eventManager.fire(any<Any>()) } answers {
            val event = firstArg<Any>()
            if (event is LoginHandleRequestEvent) {
                event.claim("module-a") { CompletableFuture.completedFuture(LoginHandleResult.failed("a failed")) }
                event.claim("module-b") { CompletableFuture.completedFuture(LoginHandleResult.failed("b failed")) }
            }
            CompletableFuture.completedFuture(event)
        }

        val resultFuture = LoginManager(proxyServer, userService, timeoutMillis = 300).dispatch(player, hyperPlayer)
        channel.runUntilComplete(resultFuture)
        val result = resultFuture.join()

        assertEquals(LoginManager.Status.ALL_FAILED, result.status)
        assertEquals(2, result.failures.size)
    }

    @Test
    fun `dispatch short-circuits on first success and cancels others`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val userService = mockk<HyperZoneUserService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>(relaxed = true)
        val channel = EmbeddedChannel()
        val attached = AtomicBoolean(false)
        val slowFuture = CompletableFuture<LoginHandleResult>()
        val credential = mockk<HyperZoneCredential>(relaxed = true)
        val attachedUser = User(
            id = UUID.randomUUID(),
        )
        every { proxyServer.eventManager } returns eventManager
        HyperZoneUserServiceProvider.bind(userService)
        mockkStatic("icu.h2l.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedUser() } answers { attached.get() }
        every { userService.getAttachedUser(hyperPlayer) } returns null
        every { userService.resolveAuthenticatedUser(hyperPlayer) } returns attachedUser
        every { userService.attachUser(hyperPlayer, attachedUser.id) } answers {
            attached.set(true)
            attachedUser
        }
        every { eventManager.fire(any<Any>()) } answers {
            val event = firstArg<Any>()
            if (event is LoginHandleRequestEvent) {
                event.claim("winner") {
                    CompletableFuture.completedFuture(LoginHandleResult.success(credential = credential))
                }
                event.claim("slow") { slowFuture }
            }
            CompletableFuture.completedFuture(event)
        }

        val resultFuture = LoginManager(proxyServer, userService, timeoutMillis = 400).dispatch(player, hyperPlayer)
        channel.runUntilComplete(resultFuture)
        val result = resultFuture.join()

        assertEquals(LoginManager.Status.SUCCESS, result.status)
        assertEquals("winner", result.winnerModuleId)
        assertTrue(slowFuture.isCancelled)
        verify(exactly = 1) { userService.resolveAuthenticatedUser(hyperPlayer) }
        verify(exactly = 1) { userService.attachUser(hyperPlayer, attachedUser.id) }
    }

    @Test
    fun `dispatch returns timeout for hanging claim`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val userService = mockk<HyperZoneUserService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>(relaxed = true)
        val channel = EmbeddedChannel()
        every { proxyServer.eventManager } returns eventManager
        HyperZoneUserServiceProvider.bind(userService)
        mockkStatic("icu.h2l.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedUser() } returns false
        every { eventManager.fire(any<Any>()) } answers {
            val event = firstArg<Any>()
            if (event is LoginHandleRequestEvent) {
                event.claim("hang") { CompletableFuture<LoginHandleResult>() }
            }
            CompletableFuture.completedFuture(event)
        }

        val result = LoginManager(proxyServer, userService, timeoutMillis = 60).dispatch(player, hyperPlayer).join()

        assertEquals(LoginManager.Status.TIMEOUT, result.status)
    }

    @Test
    fun `failed claim does not create in-game profile`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val userService = mockk<HyperZoneUserService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>(relaxed = true)
        val channel = EmbeddedChannel()
        every { proxyServer.eventManager } returns eventManager
        HyperZoneUserServiceProvider.bind(userService)
        mockkStatic("icu.h2l.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedUser() } returns false
        every { eventManager.fire(any<Any>()) } answers {
            val event = firstArg<Any>()
            if (event is LoginHandleRequestEvent) {
                event.claim("failed") {
                    CompletableFuture.completedFuture(LoginHandleResult.failed("failed"))
                }
            }
            CompletableFuture.completedFuture(event)
        }

        val manager = LoginManager(proxyServer, userService, timeoutMillis = 300)
        val resultFuture = manager.dispatch(player, hyperPlayer)
        channel.runUntilComplete(resultFuture)
        val result = resultFuture.join()

        assertEquals(LoginManager.Status.ALL_FAILED, result.status)
        verify(exactly = 0) { userService.resolveAuthenticatedUser(hyperPlayer) }
        verify(exactly = 0) { userService.createInGameProfile(any(), any()) }
    }

    @Test
    fun `attach derives candidate from submitted credential`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val userService = mockk<HyperZoneUserService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>(relaxed = true)
        val channel = EmbeddedChannel()
        val attached = AtomicBoolean(false)
        val credential = mockk<HyperZoneCredential>(relaxed = true)
        val gameUuid = UUID.randomUUID()
        val candidate = InGameProfileCandidate(
            channel = "test",
            name = "Candidate",
            uuid = gameUuid,
        )
        val attachedUser = User(
            id = UUID.randomUUID(),
        )
        val inGameProfile = mockk<InGameProfile>(relaxed = true)
        every { proxyServer.eventManager } returns eventManager
        HyperZoneUserServiceProvider.bind(userService)
        mockkStatic("icu.h2l.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedUser() } answers { attached.get() }
        every { credential.channelId } returns "test"
        every { credential.getRegistrationName() } returns "Candidate"
        every { credential.getSuggestedGameUuid() } returns gameUuid
        every { userService.getAttachedUser(hyperPlayer) } returns null
        every { userService.resolveAuthenticatedUser(hyperPlayer) } returns attachedUser
        every {
            userService.findInGameProfileByUuid(gameUuid)
        } returns null
        every { userService.createInGameProfile(attachedUser.id, candidate) } returns inGameProfile
        every { userService.attachUser(hyperPlayer, attachedUser.id) } answers {
            attached.set(true)
            attachedUser
        }
        every { eventManager.fire(any<Any>()) } answers {
            val event = firstArg<Any>()
            if (event is LoginHandleRequestEvent) {
                event.claim("winner") {
                    CompletableFuture.completedFuture(
                        LoginHandleResult.success(credential = credential)
                    )
                }
            }
            CompletableFuture.completedFuture(event)
        }

        val manager = LoginManager(proxyServer, userService, timeoutMillis = 400)
        val resultFuture = manager.dispatch(player, hyperPlayer)
        channel.runUntilComplete(resultFuture)
        val result = resultFuture.join()

        assertEquals(LoginManager.Status.SUCCESS, result.status)
        verify(exactly = 1) {
            userService.createInGameProfile(attachedUser.id, candidate)
        }
    }

    @Test
    fun `attach reuses existing identity without name consistency check`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val userService = mockk<HyperZoneUserService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>(relaxed = true)
        val channel = EmbeddedChannel()
        val userUUID = UUID.randomUUID()
        val existing = InGameProfile(
            id = UUID.randomUUID(),
            userUUID = userUUID,
            name = "OldName",
            uuid = UUID.randomUUID(),
        )
        val credential = mockk<HyperZoneCredential>(relaxed = true)
        val attachedUser = User(
            id = userUUID,
        )
        every { proxyServer.eventManager } returns eventManager
        HyperZoneUserServiceProvider.bind(userService)
        mockkStatic("icu.h2l.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { credential.channelId } returns "test"
        every { credential.getRegistrationName() } returns "NewName"
        every { credential.getSuggestedGameUuid() } returns existing.uuid
        every {
            userService.findInGameProfileByUuid(existing.uuid)
        } returns existing
        every { userService.attachUser(hyperPlayer, userUUID) } returns attachedUser

        val manager = LoginManager(proxyServer, userService, timeoutMillis = 400)
        manager.submitCredential(hyperPlayer, credential)

        val result = manager.attachUser(hyperPlayer, userUUID)

        assertEquals(userUUID, result?.id)
        verify(exactly = 0) { userService.createInGameProfile(any(), any()) }
        verify(exactly = 1) { userService.attachUser(hyperPlayer, userUUID) }
    }

    private fun EmbeddedChannel.runUntilComplete(future: CompletableFuture<*>) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (!future.isDone && System.nanoTime() < deadline) {
            runPendingTasks()
            Thread.sleep(1)
        }
    }
}

