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

import com.velocitypowered.api.event.EventManager
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import icu.h2l.api.HyperZoneApi
import icu.h2l.api.event.auth.LoginHandleRequestEvent
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.database.DatabaseConfig
import icu.h2l.login.database.DatabaseHelper
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.manager.LoginManager
import icu.h2l.login.message.MessageService
import icu.h2l.login.profile.VelocityHyperZoneProfileService
import icu.h2l.login.vServer.outpre.handler.OutPreAuthSessionHandler
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.netty.channel.embedded.EmbeddedChannel
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CompletableFuture

class OutPreVServerAuthTest {
    private val sessions = mutableListOf<TestSession>()

    @Test
    fun `beginInitialJoin defers auth start until auth server joined`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        every { proxyServer.eventManager } returns eventManager

        val outPre = spyk(OutPreVServerAuth(proxyServer))
        bootstrapMain(proxyServer, outPre)
        val session = createSession(isActiveStates = listOf(true))
        val bridge = mockk<OutPreBackendBridge>(relaxed = true)
        every { bridge.connect() } returns CompletableFuture.completedFuture(mockk(relaxed = true))
        val bridgeCallbackSlot = io.mockk.slot<OutPreBackendBridge.Callback>()
        every { bridge.bindSession(capture(bridgeCallbackSlot)) } just runs
        every { outPre.createBridge(any()) } returns bridge
        val handler = mockk<OutPreAuthSessionHandler>(relaxed = true)
        val statesField = OutPreVServerAuth::class.java.getDeclaredField("states").apply {
            isAccessible = true
        }

        var authRequestFired = false
        var stateWasVisibleDuringAuthRequest = false
        var authServerJoinedAtAuthRequest = false

        every { eventManager.fire(any<Any>()) } answers {
            val event = firstArg<Any>()
            if (event is LoginHandleRequestEvent) {
                authRequestFired = true
                val state = currentState(outPre, session.channel, statesField)
                stateWasVisibleDuringAuthRequest = state != null
                authServerJoinedAtAuthRequest = state?.readBoolean("hasConnectedToAuthServerOnce") == true
            }
            CompletableFuture.completedFuture(event)
        }

        outPre.beginInitialJoin(session.player, handler)
        assertFalse(authRequestFired)

        bridgeCallbackSlot.captured.onJoined()

        assertTrue(authRequestFired)
        assertTrue(stateWasVisibleDuringAuthRequest)
        assertTrue(authServerJoinedAtAuthRequest)
        assertTrue(hasState(outPre, session.channel, statesField))
    }

    @AfterEach
    fun cleanupSessions() {
        sessions.forEach { session ->
            HyperZonePlayerManager.remove(session.player)
            session.channel.finishAndReleaseAll()
        }
        sessions.clear()
    }

    private fun bootstrapMain(proxyServer: ProxyServer, outPre: OutPreVServerAuth): HyperZoneLoginMain {
        return HyperZoneLoginMain(
            server = proxyServer,
            logger = mockk<ComponentLogger>(relaxed = true),
            dataDirectory = Paths.get("build", "tmp", "outpre-vserver-auth-test"),
            plugin = mockk<HyperZoneApi>(relaxed = true),
        ).also { main ->
            main.activeVServerAdapter = outPre
            main.profileService = createProfileService(proxyServer)
            main.messageService = MessageService(main.dataDirectory, main.logger)
            main.loginManager = LoginManager(proxyServer, main.profileService)
                setCoreConfig(icu.h2l.login.config.CoreConfig())
        }
    }

    private fun createProfileService(proxyServer: ProxyServer): VelocityHyperZoneProfileService {
        val databaseManager = icu.h2l.login.manager.DatabaseManager(
            config = DatabaseConfig.sqlite(path = ":memory:"),
            proxy = proxyServer,
        )
        return VelocityHyperZoneProfileService(DatabaseHelper(databaseManager))
    }

    private fun createSession(isActiveStates: List<Boolean>, isOnline: Boolean = false): TestSession {
        val channel = EmbeddedChannel()
        val connection = mockk<MinecraftConnection>(relaxed = true)
        every { connection.channel } returns channel
        every { connection.eventLoop() } returns channel.eventLoop()

        val player = mockk<ConnectedPlayer>(relaxed = true)
        every { player.connection } returns connection
        every { player.username } returns "BedrockUser"
        every { player.isActive } returnsMany isActiveStates andThen isActiveStates.last()
        every { player.disconnect(any()) } just runs

        HyperZonePlayerManager.create(
            channel = channel,
            userName = "BedrockUser",
            uuid = TEST_UUID,
            isOnline = isOnline,
        )

        return TestSession(channel, player).also(sessions::add)
    }

    private fun currentState(outPre: OutPreVServerAuth, channel: EmbeddedChannel, statesField: java.lang.reflect.Field): Any? {
        @Suppress("UNCHECKED_CAST")
        val states = statesField.get(outPre) as MutableMap<io.netty.channel.Channel, Any>
        return states[channel]
    }

    private fun hasState(outPre: OutPreVServerAuth, channel: EmbeddedChannel, statesField: java.lang.reflect.Field): Boolean {
        return currentState(outPre, channel, statesField) != null
    }

    private fun Any.readBoolean(fieldName: String): Boolean {
        val field = javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }
        return field.getBoolean(this)
    }

    private fun setCoreConfig(value: Any) {
        val field = HyperZoneLoginMain::class.java.getDeclaredField("coreConfig").apply {
            isAccessible = true
        }
        field.set(null, value)
    }

    private data class TestSession(
        val channel: EmbeddedChannel,
        val player: ConnectedPlayer,
    )

    companion object {
        private val TEST_UUID: UUID = UUID.fromString("88888888-8888-4888-8888-888888888888")
    }
}




