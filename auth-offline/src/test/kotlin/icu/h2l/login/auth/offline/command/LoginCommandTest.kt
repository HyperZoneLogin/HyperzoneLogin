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

package icu.h2l.login.auth.offline.command

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.Profile
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.profile.HyperZoneCredentialFlow
import icu.h2l.api.profile.HyperZoneCredentialFlowProvider
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.login.auth.offline.OfflineAuthMessages
import icu.h2l.login.auth.offline.api.db.OfflineAuthTable
import icu.h2l.login.auth.offline.config.AuthOfflineConfigLoader
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import icu.h2l.login.auth.offline.mail.OfflineAuthEmailSender
import icu.h2l.login.auth.offline.service.OfflineAuthService
import icu.h2l.login.auth.offline.service.PendingOfflineRegistrationManager
import icu.h2l.login.auth.offline.totp.OfflineTotpAuthenticator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class LoginCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var repository: OfflineAuthRepository
    private lateinit var player: Player
    private lateinit var hyperZonePlayer: HyperZonePlayer
    private lateinit var credentialFlow: HyperZoneCredentialFlow
    private lateinit var profileService: HyperZoneProfileService
    private lateinit var command: LoginCommand
    private lateinit var invocation: HyperChatCommandInvocation
    private lateinit var profileTable: ProfileTable
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        AuthOfflineConfigLoader.load(tempDir)

        database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        profileTable = ProfileTable()
        val offlineAuthTable = OfflineAuthTable("", profileTable)
        val databaseManager = object : HyperZoneDatabaseManager {
            override val tablePrefix: String = ""

            override fun <T> executeTransaction(statement: () -> T): T {
                return transaction(database) { statement() }
            }
        }
        transaction(database) {
            SchemaUtils.create(profileTable, offlineAuthTable)
        }

        repository = OfflineAuthRepository(databaseManager, offlineAuthTable)
        player = mockk(relaxUnitFun = true)
        hyperZonePlayer = mockk(relaxUnitFun = true)
        credentialFlow = mockk(relaxUnitFun = true)
        HyperZoneCredentialFlowProvider.bind(credentialFlow)
        profileService = mockk()
        invocation = mockk()

        every { hyperZonePlayer.clientOriginalName } returns USERNAME
        every { hyperZonePlayer.getSubmittedCredentials() } returns emptyList()
        every { hyperZonePlayer.hasAttachedProfile() } returns false

        val service = OfflineAuthService(
            repository = repository,
            pendingRegistrations = PendingOfflineRegistrationManager(),
            playerAccessor = TestPlayerAccessor(hyperZonePlayer),
            profileService = profileService,
            emailSender = NoopEmailSender,
            totpAuthenticator = OfflineTotpAuthenticator("HyperZoneLogin-Test", 5),
            proxy = mockk<ProxyServer>(relaxed = true)
        )
        command = LoginCommand(service)
    }

    @Test
    fun `legacy login syntax still logs in by current connection name`() {
        insertProfile()
        repository.create(
            name = NORMALIZED_NAME,
            passwordHash = hashPassword(VALID_PASSWORD),
            hashFormat = "sha256",
            profileId = PROFILE.id
        )

        every { hyperZonePlayer.hasAttachedProfile() } returns false
        every { profileService.getAttachedProfile(hyperZonePlayer) } returns null
        every { invocation.source() } returns player
        every { invocation.arguments() } returns arrayOf(VALID_PASSWORD)

        command.execute(invocation)

        verify(exactly = 1) { credentialFlow.overVerify(hyperZonePlayer) }
        verify(exactly = 1) { player.sendMessage(OfflineAuthMessages.LOGIN_SUCCESS) }
    }

    @Test
    fun `explicit username syntax logs in the selected offline account`() {
        insertProfile()
        repository.create(
            name = NORMALIZED_NAME,
            passwordHash = hashPassword(VALID_PASSWORD),
            hashFormat = "sha256",
            profileId = PROFILE.id
        )

        every { hyperZonePlayer.hasAttachedProfile() } returns false
        every { hyperZonePlayer.clientOriginalName } returns OTHER_NAME
        every { profileService.getAttachedProfile(hyperZonePlayer) } returns null
        every { invocation.source() } returns player
        every { invocation.arguments() } returns arrayOf("as", USERNAME, VALID_PASSWORD)

        command.execute(invocation)

        verify(exactly = 1) { credentialFlow.overVerify(hyperZonePlayer) }
        verify(exactly = 1) { player.sendMessage(OfflineAuthMessages.LOGIN_SUCCESS) }
    }

    @Test
    fun `invalid login syntax shows usage`() {
        every { invocation.source() } returns player
        every { invocation.arguments() } returns arrayOf("as", USERNAME)

        command.execute(invocation)

        verify(exactly = 0) { credentialFlow.overVerify(hyperZonePlayer) }
        verify(exactly = 1) { player.sendMessage(OfflineAuthMessages.LOGIN_USAGE) }
    }

    private fun insertProfile() {
        transaction(database) {
            profileTable.insert {
                it[id] = PROFILE.id
                it[name] = PROFILE.name
                it[uuid] = PROFILE.uuid
            }
        }
    }

    private object NoopEmailSender : OfflineAuthEmailSender {
        override fun sendRecoveryCode(message: OfflineAuthEmailSender.RecoveryCodeMailMessage): OfflineAuthEmailSender.DeliveryResult {
            return OfflineAuthEmailSender.DeliveryResult(success = true)
        }
    }

    private class TestPlayerAccessor(
        private val hyperZonePlayer: HyperZonePlayer
    ) : HyperZonePlayerAccessor {
        override fun create(
            channel: io.netty.channel.Channel,
            userName: String,
            uuid: UUID?,
            isOnline: Boolean
        ): HyperZonePlayer {
            return hyperZonePlayer
        }

        override fun getByPlayer(player: Player): HyperZonePlayer {
            return hyperZonePlayer
        }

        override fun getByChannel(channel: io.netty.channel.Channel): HyperZonePlayer {
            return hyperZonePlayer
        }
    }

    companion object {
        private const val USERNAME = "Alice"
        private const val OTHER_NAME = "GuestUser"
        private const val NORMALIZED_NAME = "alice"
        private const val VALID_PASSWORD = "SecurePass123"
        private val PROFILE = Profile(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            name = USERNAME,
            uuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        )

        private fun hashPassword(password: String): String {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
