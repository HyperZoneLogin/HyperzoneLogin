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

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.User
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.manager.LoginManager
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.message.MessageService
import icu.h2l.login.player.VelocityHyperZonePlayer
import io.mockk.*
import io.netty.channel.embedded.EmbeddedChannel
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import util.MockStaticUtil
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.util.*

class UnAttachedPlayerListenerTest {
    private val sessions = mutableListOf<TestSession>()
    private val authServer = registeredServer("auth", 30001)
    private val playServer = registeredServer("play", 30002)
    private val disconnectMessage: Component = Component.text("misconfigured")
    private lateinit var main: HyperZoneLoginMain
    private lateinit var adapter: HyperZoneVServerAdapter
    private lateinit var api: HyperZoneApi
    private lateinit var gate: UnAttachedPlayerListener

    @BeforeEach
    fun setUp() {
        adapter = mockk()
        every { adapter.getRegisteredVServer() } returns authServer
        every { adapter.isPlayerInWaitingArea(any()) } returns false

        val messageService = mockk<MessageService>()
        every {
            messageService.render(
                any<CommandSource>(),
                MessageKeys.BackendAuth.MISCONFIGURED_DISCONNECT,
                *anyVararg<HyperZoneMessagePlaceholder>()
            )
        } returns disconnectMessage

        main = HyperZoneLoginMain(
            server = mockk<ProxyServer>(relaxed = true),
            logger = mockk<ComponentLogger>(relaxed = true),
            dataDirectory = Paths.get("build", "tmp", "unattached-connection-gate-test"),
            plugin = mockk<HyperZoneApi>(relaxed = true),
        ).also {
            it.activeVServerAdapter = adapter
            it.messageService = messageService
            it.loginManager = mockk<LoginManager>(relaxed = true)
        }

        api = mockk()
        every { api.hyperZonePlayers } returns HyperZonePlayerManager
        every { api.serverAdapter } answers { main.serverAdapter }
        every { api.getRegisteredVServer() } answers { callOriginal() }
        gate = UnAttachedPlayerListener(api)
    }

    @AfterEach
    fun cleanupSessions() {
        sessions.forEach { session ->
            HyperZonePlayerManager.remove(session.player)
            session.channel.finishAndReleaseAll()
        }
        sessions.clear()
        MockStaticUtil.clear()
    }

    @Test
    fun `attached player keeps the result chosen by earlier listeners`() {
        val session = createSession("Attached", attached = true, currentServer = authServer)
        val event = ServerPreConnectEvent(session.player, authServer, authServer)

        dispatch(event) { it.result = ServerPreConnectEvent.ServerResult.allowed(playServer) }

        assertSame(playServer, event.result.server.orElse(null))
        verify(exactly = 0) { api.getRegisteredVServer() }
        verify(exactly = 0) { session.player.disconnect(any()) }
    }

    @Test
    fun `unattached player in waiting area stays when a later listener re-allows another server`() {
        val session = createSession("Waiting", attached = false, currentServer = authServer)
        every { adapter.isPlayerInWaitingArea(session.player) } returns true
        val event = ServerPreConnectEvent(session.player, playServer, authServer)

        dispatch(event) {
            it.result = ServerPreConnectEvent.ServerResult.denied()
            it.result = ServerPreConnectEvent.ServerResult.allowed(playServer)
        }

        assertFalse(event.result.isAllowed)
        verify(exactly = 0) { session.player.disconnect(any()) }
    }

    @Test
    fun `unattached player without backend is redirected to waiting area`() {
        val session = createSession("Initial", attached = false)
        val event = ServerPreConnectEvent(session.player, playServer, null)

        dispatch(event)

        assertSame(authServer, event.result.server.orElse(null))
        verify(exactly = 0) { session.player.disconnect(any()) }
    }

    @Test
    fun `unattached connection to waiting area is kept`() {
        val session = createSession("ToAuth", attached = false)
        val waitingAreaAlias = registeredServer("auth", 30001)
        val event = ServerPreConnectEvent(session.player, waitingAreaAlias, null)

        dispatch(event)

        assertSame(waitingAreaAlias, event.result.server.orElse(null))
        verify(exactly = 0) { session.player.disconnect(any()) }
    }

    @Test
    fun `server with waiting area name but another address is not the waiting area`() {
        val session = createSession("Impostor", attached = false, currentServer = authServer)
        val impostor = registeredServer("auth", 40001)
        val event = ServerPreConnectEvent(session.player, impostor, authServer)

        dispatch(event)

        assertFalse(event.result.isAllowed)
    }

    @Test
    fun `already denied connection is left untouched`() {
        val session = createSession("Denied", attached = false)
        val event = ServerPreConnectEvent(session.player, playServer, null)

        dispatch(event) { it.result = ServerPreConnectEvent.ServerResult.denied() }

        assertFalse(event.result.isAllowed)
        verify(exactly = 0) { api.getRegisteredVServer() }
        verify(exactly = 0) { session.player.disconnect(any()) }
    }

    @Test
    fun `unattached player is disconnected when no waiting-area adapter is active`() {
        main.activeVServerAdapter = null
        val session = createSession("NoAdapter", attached = false)
        val event = ServerPreConnectEvent(session.player, playServer, null)

        dispatch(event)

        assertFalse(event.result.isAllowed)
        verify(exactly = 1) { session.player.disconnect(disconnectMessage) }
    }

    @Test
    fun `unattached player is disconnected when adapter has no waiting area for it`() {
        every { adapter.getRegisteredVServer() } returns null
        val session = createSession("Disabled", attached = false)
        val event = ServerPreConnectEvent(session.player, playServer, null)

        dispatch(event)

        assertFalse(event.result.isAllowed)
        verify(exactly = 1) { session.player.disconnect(disconnectMessage) }
    }

    @Test
    fun `adapter holding the player without a waiting server denies without disconnect`() {
        every { adapter.getRegisteredVServer() } returns null
        val session = createSession("Bridged", attached = false)
        every { adapter.isPlayerInWaitingArea(session.player) } returns true
        val event = ServerPreConnectEvent(session.player, playServer, null)

        dispatch(event)

        assertFalse(event.result.isAllowed)
        verify(exactly = 0) { session.player.disconnect(any()) }
    }

    @Test
    fun `kick fallback from waiting area is denied instead of redirected back`() {
        val session = createSession("Kicked", attached = false)
        val event = ServerPreConnectEvent(session.player, playServer, authServer)

        dispatch(event)

        assertFalse(event.result.isAllowed)
        verify(exactly = 0) { session.player.disconnect(any()) }
    }

    @Test
    fun `connection without HyperZonePlayer is denied and disconnected`() {
        val session = createSession("Untracked", attached = false, tracked = false)
        val event = ServerPreConnectEvent(session.player, authServer, null)

        dispatch(event)

        assertFalse(event.result.isAllowed)
        verify(exactly = 1) { session.player.disconnect(disconnectMessage) }
    }

    @Test
    fun `adapter failure denies the connection`() {
        every { adapter.getRegisteredVServer() } throws IllegalStateException("adapter failure")
        val session = createSession("Failing", attached = false)
        val event = ServerPreConnectEvent(session.player, playServer, null)

        dispatch(event)

        assertFalse(event.result.isAllowed)
    }

    private fun dispatch(
        event: ServerPreConnectEvent,
        otherListeners: (ServerPreConnectEvent) -> Unit = {},
    ) {
        otherListeners(event)
        gate.onServerPreConnect(event)
    }

    private fun createSession(
        name: String,
        attached: Boolean,
        currentServer: RegisteredServer? = null,
        tracked: Boolean = true,
    ): TestSession {
        val channel = EmbeddedChannel()
        val connection = mockk<MinecraftConnection>(relaxed = true)
        every { connection.channel } returns channel

        val player = mockk<ConnectedPlayer>(relaxed = true)
        every { player.connection } returns connection
        every { player.username } returns name
        every { player.currentServer } returns Optional.ofNullable<ServerConnection>(currentServer?.let(::serverConnection))
        every { player.disconnect(any()) } just runs

        val hyperZonePlayer = if (tracked) {
            HyperZonePlayerManager.create(
                channel = channel,
                loginName = name,
                loginUuid = UUID.randomUUID(),
                isOnline = false,
            ) as VelocityHyperZonePlayer
        } else {
            null
        }
        if (attached) {
            requireNotNull(hyperZonePlayer).bindAttachedUser(User(id = UUID.randomUUID()), null)
        }

        return TestSession(channel, player, hyperZonePlayer).also(sessions::add)
    }

    private fun registeredServer(name: String, port: Int): RegisteredServer {
        val server = mockk<RegisteredServer>()
        every { server.serverInfo } returns ServerInfo(name, InetSocketAddress.createUnresolved("127.0.0.1", port))
        return server
    }

    private fun serverConnection(server: RegisteredServer): ServerConnection {
        val connection = mockk<ServerConnection>()
        every { connection.server } returns server
        every { connection.serverInfo } returns server.serverInfo
        return connection
    }

    private data class TestSession(
        val channel: EmbeddedChannel,
        val player: ConnectedPlayer,
        val hyperZonePlayer: VelocityHyperZonePlayer?,
    )
}
