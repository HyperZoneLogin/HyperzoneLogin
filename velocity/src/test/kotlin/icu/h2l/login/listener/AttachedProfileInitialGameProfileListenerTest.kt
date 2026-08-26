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

import com.velocitypowered.api.event.EventManager
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.connection.client.InitialInboundConnection
import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.Profile
import icu.h2l.api.event.profile.VerifyInitialGameProfileEvent
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.database.DatabaseConfig
import icu.h2l.login.database.DatabaseHelper
import icu.h2l.login.manager.DatabaseManager
import icu.h2l.login.manager.LoginManager
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.VelocityHyperZonePlayer
import icu.h2l.login.profile.VelocityHyperZoneProfileService
import io.mockk.every
import io.mockk.mockk
import io.netty.channel.Channel
import io.netty.channel.embedded.EmbeddedChannel
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CompletableFuture

class AttachedProfileInitialGameProfileListenerTest {
    private val listener = AttachedProfileInitialGameProfileListener()
    private val sessions = mutableListOf<TestSession>()

    @Test
    fun `listener bypasses matching attached formal profile`() {
        val main = bootstrapMain()
        val session = createSession()
        attachFormalProfile(main.profileService, session.hyperPlayer)
        val event = verifyEvent(
            session.channel,
            GameProfile(TEST_UUID, "FormalUser", listOf(GameProfile.Property("textures", "value", "sig"))),
        )

        listener.onVerifyInitialGameProfileEvent(event)

        assertTrue(event.pass)
    }

    @Test
    fun `listener keeps verify gate closed for attached profile identity mismatch`() {
        val main = bootstrapMain()
        val session = createSession()
        attachFormalProfile(main.profileService, session.hyperPlayer)
        val event = verifyEvent(session.channel, GameProfile(TEST_UUID, "OtherUser", emptyList()))

        listener.onVerifyInitialGameProfileEvent(event)

        assertFalse(event.pass)
    }

    @Test
    fun `listener keeps verify gate closed when hyper player has no attached formal profile`() {
        bootstrapMain()
        val session = createSession()
        val event = verifyEvent(session.channel, GameProfile(TEST_UUID, "FormalUser", emptyList()))

        listener.onVerifyInitialGameProfileEvent(event)

        assertFalse(event.pass)
    }

    @Test
    fun `listener keeps verify gate closed when hyper player is missing`() {
        bootstrapMain()
        val channel = EmbeddedChannel().also { transientChannels += it }
        val event = verifyEvent(channel, GameProfile(TEST_UUID, "FormalUser", emptyList()))

        listener.onVerifyInitialGameProfileEvent(event)

        assertFalse(event.pass)
    }

    @Test
    fun `listener bypasses matching attached profile even for non outpre adapters`() {
        val main = bootstrapMain()
        val session = createSession()
        main.activeVServerAdapter = mockk<HyperZoneVServerAdapter>(relaxed = true)
        attachFormalProfile(main.profileService, session.hyperPlayer)
        val event = verifyEvent(session.channel, GameProfile(TEST_UUID, "FormalUser", emptyList()))

        listener.onVerifyInitialGameProfileEvent(event)

        assertTrue(event.pass)
    }

    @AfterEach
    fun cleanupSessions() {
        sessions.forEach { session ->
            HyperZonePlayerManager.remove(session.proxyPlayer)
            session.channel.finishAndReleaseAll()
        }
        sessions.clear()
        transientChannels.forEach(EmbeddedChannel::finishAndReleaseAll)
        transientChannels.clear()
    }

    private fun bootstrapMain(): HyperZoneLoginMain {
        val eventManager = mockk<EventManager>()
        every { eventManager.fire(any<Any>()) } answers {
            CompletableFuture.completedFuture(firstArg<Any>())
        }
        val proxyServer = mockk<ProxyServer>()
        every { proxyServer.eventManager } returns eventManager
        every { proxyServer.allPlayers } returns emptyList<Player>()

        return HyperZoneLoginMain(
            server = proxyServer,
            logger = mockk<ComponentLogger>(relaxed = true),
            dataDirectory = Paths.get("build", "tmp", "outpre-verify-listener-test"),
            plugin = mockk<HyperZoneApi>(relaxed = true),
        ).also { main ->
            main.profileService = createProfileService(proxyServer)
            main.loginManager = mockk<LoginManager>(relaxed = true)
        }
    }

    private fun createProfileService(proxyServer: ProxyServer): VelocityHyperZoneProfileService {
        val databaseManager = DatabaseManager(
            config = DatabaseConfig.sqlite(path = ":memory:"),
            proxy = proxyServer,
        )
        return VelocityHyperZoneProfileService(DatabaseHelper(databaseManager))
    }

    private fun attachFormalProfile(
        profileService: VelocityHyperZoneProfileService,
        player: VelocityHyperZonePlayer,
    ): Profile {
        val profile = Profile(TEST_PROFILE_ID, "FormalUser", TEST_UUID)
        seedProfileCache(profileService, profile)
        return profileService.attachProfile(player, profile.id)
            ?: error("Failed to attach formal profile for test")
    }

    private fun seedProfileCache(profileService: VelocityHyperZoneProfileService, profile: Profile) {
        val helperField = VelocityHyperZoneProfileService::class.java.getDeclaredField("databaseHelper").apply {
            isAccessible = true
        }
        val databaseHelper = helperField.get(profileService) as DatabaseHelper
        CACHE_PROFILE_METHOD.invoke(databaseHelper, profile)
    }

    private fun createSession(): TestSession {
        val channel = EmbeddedChannel()
        val proxyPlayer = player(channel)
        val hyperPlayer = HyperZonePlayerManager.create(channel, "ClientUser", TEST_UUID, true) as VelocityHyperZonePlayer
        return TestSession(channel, proxyPlayer, hyperPlayer).also(sessions::add)
    }

    private fun player(channel: Channel): ConnectedPlayer {
        val player = mockk<ConnectedPlayer>(relaxed = true)
        val connection = mockk<MinecraftConnection>(relaxed = true)
        every { connection.channel } returns channel
        every { player.connection } returns connection
        return player
    }

    private fun verifyEvent(channel: Channel, profile: GameProfile): VerifyInitialGameProfileEvent {
        val minecraftConnection = mockk<MinecraftConnection>(relaxed = true)
        every { minecraftConnection.channel } returns channel
        val connection = mockk<InitialInboundConnection>(relaxed = true)
        every { connection.connection } returns minecraftConnection

        return VerifyInitialGameProfileEvent(
            connection = connection,
            gameProfile = profile,
        )
    }

    companion object {
        private val TEST_UUID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        private val TEST_PROFILE_ID: UUID = UUID.fromString("55555555-5555-5555-8555-555555555555")
        private val CACHE_PROFILE_METHOD: Method = DatabaseHelper::class.java.getDeclaredMethod("cacheProfile", Profile::class.java).apply {
            isAccessible = true
        }
    }

    private val transientChannels = mutableListOf<EmbeddedChannel>()

    private data class TestSession(
        val channel: EmbeddedChannel,
        val proxyPlayer: ConnectedPlayer,
        val hyperPlayer: VelocityHyperZonePlayer,
    )
}






