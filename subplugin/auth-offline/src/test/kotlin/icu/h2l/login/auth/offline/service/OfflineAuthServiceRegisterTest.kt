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

package icu.h2l.login.auth.offline.service

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.User
import icu.h2l.api.db.table.UserTable
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneCredentialFlow
import icu.h2l.api.profile.HyperZoneCredentialFlowProvider
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.login.auth.offline.OfflineAuthMessages
import icu.h2l.login.auth.offline.TestMessageService
import icu.h2l.login.auth.offline.api.db.OfflineAuthTable
import icu.h2l.login.auth.offline.config.AuthOfflineConfigLoader
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import icu.h2l.login.auth.offline.mail.OfflineAuthEmailSender
import icu.h2l.login.auth.offline.totp.OfflineTotpAuthenticator
import icu.h2l.login.auth.offline.util.ExtraUuidUtils
import io.mockk.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class OfflineAuthServiceRegisterTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var repository: OfflineAuthRepository
    private lateinit var player: Player
    private lateinit var hyperZonePlayer: HyperZonePlayer
    private lateinit var proxy: ProxyServer
    private lateinit var userService: HyperZoneUserService
    private lateinit var credentialFlow: HyperZoneCredentialFlow
    private lateinit var service: OfflineAuthService
    private lateinit var pendingRegistrations: PendingOfflineRegistrationManager
    private lateinit var userTable: UserTable
    private lateinit var offlineAuthTable: OfflineAuthTable
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        TestMessageService.install()
        AuthOfflineConfigLoader.load(tempDir)
        AuthOfflineConfigLoader.getConfig().main.passOfflineUuidToProfileResolve = false

        database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        userTable = UserTable()
        offlineAuthTable = OfflineAuthTable("", userTable)
        val databaseManager = object : HyperZoneDatabaseManager {
            override val tablePrefix: String = ""

            override fun <T> executeTransaction(statement: () -> T): T {
                return transaction(database) { statement() }
            }
        }
        transaction(database) {
            SchemaUtils.create(userTable, offlineAuthTable)
        }

        repository = OfflineAuthRepository(databaseManager, offlineAuthTable)
        player = mockk(relaxed = true)
        hyperZonePlayer = mockk(relaxUnitFun = true)
        proxy = mockk(relaxed = true)
        userService = mockk()
        credentialFlow = mockk(relaxUnitFun = true)
        HyperZoneCredentialFlowProvider.bind(credentialFlow)
        pendingRegistrations = PendingOfflineRegistrationManager()

        every { hyperZonePlayer.logLabel } returns USERNAME
        every { player.username } returns USERNAME
        every { hyperZonePlayer.getSubmittedCredentials() } returns emptyList()
        every { hyperZonePlayer.hasAttachedUser() } returns false
        every { hyperZonePlayer.canBind() } returns true
        service = OfflineAuthService(
            repository = repository,
            pendingRegistrations = pendingRegistrations,
            playerAccessor = TestPlayerAccessor(hyperZonePlayer),
            userService = userService,
            emailSender = NoopEmailSender,
            totpAuthenticator = OfflineTotpAuthenticator("HyperZoneLogin-Test", 5),
            proxy = proxy
        )
    }

    @Test
    fun `register creates a new offline password entry when player can resolve profile`() {
        insertProfile()

        every { userService.getAttachedUser(hyperZonePlayer) } returns null
        // 服务现在通过凭证与 ProfileService 交互；passthrough 关闭时凭证建议 UUID 为 null
        every { userService.canCreateUser(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedGameUuid() == null
        }) } returns true
        every { userService.createUser(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedGameUuid() == null
        }) } returns PROFILE

        val result = service.register(player, VALID_PASSWORD)
        val saved = repository.getByName(NORMALIZED_NAME)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_SUCCESS, result.message)
        assertNotNull(saved)
        assertEquals(PROFILE.id, saved!!.userUUID)
        assertEquals("sha256", saved.hashFormat)
        assertEquals(64, saved.passwordHash.length)
        assertNotEquals(VALID_PASSWORD, saved.passwordHash)
        verify(exactly = 1) {
            credentialFlow.submitCredential(hyperZonePlayer, match {
                it.channelId == "offline" &&
                    it.credentialId == NORMALIZED_NAME &&
                    it.getBoundUserUUID() == PROFILE.id
            })
        }
        verify(exactly = 1) { credentialFlow.overVerify(hyperZonePlayer) }
    }

    @Test
    fun `register passes normal offline uuid to profile resolve when passthrough is enabled`() {
        enableOfflineUuidPassthrough()
        insertProfile()

        val offlineUuid = ExtraUuidUtils.getNormalOfflineUUID(NORMALIZED_NAME)
        every { userService.getAttachedUser(hyperZonePlayer) } returns null
        // 服务现在通过凭证与 ProfileService 交互；passthrough 开启时凭证建议 UUID 为离线 UUID
        every { userService.canCreateUser(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedGameUuid() == offlineUuid
        }) } returns true
        every { userService.createUser(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedGameUuid() == offlineUuid
        }) } returns PROFILE

        val result = service.register(player, VALID_PASSWORD)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_SUCCESS, result.message)
        verify(exactly = 1) { userService.canCreateUser(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedGameUuid() == offlineUuid
        }) }
        verify(exactly = 1) { userService.createUser(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedGameUuid() == offlineUuid
        }) }
        verify(exactly = 0) { userService.createUser(match<HyperZoneCredential> {
            it.getSuggestedGameUuid() == null
        }) }
    }

    @Test
    fun `register falls back to binding existing profile when direct registration is unavailable`() {
        insertProfile()

        every { userService.getAttachedUser(hyperZonePlayer) } returns PROFILE
        every { hyperZonePlayer.canBind() } returns true

        val result = service.register(player, VALID_PASSWORD)
        val saved = repository.getByProfileId(PROFILE.id)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_BOUND_SUCCESS, result.message)
        assertNotNull(saved)
        verify(exactly = 1) { userService.getAttachedUser(hyperZonePlayer) }
        verify(exactly = 0) { credentialFlow.overVerify(hyperZonePlayer) }
    }

    @Test
    fun `register reports denial when attached profile binding is not allowed`() {
        every { userService.getAttachedUser(hyperZonePlayer) } returns PROFILE
        every { hyperZonePlayer.canBind() } returns false

        val result = service.register(player, VALID_PASSWORD)

        assertFalse(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_BIND_DENIED, result.message)
    }

    @Test
    fun `register reports existing offline password when fallback binding target is already linked`() {
        insertProfile()
        repository.create(
            name = NORMALIZED_NAME,
            passwordHash = "existing-hash",
            hashFormat = "sha256",
            userUUID = PROFILE.id
        )

        every { userService.getAttachedUser(hyperZonePlayer) } returns PROFILE
        every { hyperZonePlayer.canBind() } returns true

        val result = service.register(player, VALID_PASSWORD)

        assertFalse(result.success)
        assertEquals(OfflineAuthMessages.OFFLINE_PASSWORD_ALREADY_SET, result.message)
    }

    @Test
    fun `join prompts no longer advertise direct register binding when creation is unavailable`() {
        insertProfile()

        every { hyperZonePlayer.hasAttachedUser() } returns false
        every { userService.getAttachedUser(hyperZonePlayer) } returns PROFILE

        val prompts = service.getJoinPrompts(player)

        assertTrue(prompts.contains(OfflineAuthMessages.REGISTER_REQUEST))
        assertTrue(prompts.contains(OfflineAuthMessages.LOGIN_OTHER_USERNAME_PROMPT))
        assertEquals(2, prompts.size)
    }

    @Test
    fun `login supports explicitly choosing another offline username`() {
        insertProfile()
        repository.create(
            name = NORMALIZED_NAME,
            passwordHash = hashPassword(VALID_PASSWORD),
            hashFormat = "sha256",
            userUUID = PROFILE.id
        )

        every { hyperZonePlayer.hasAttachedUser() } returns false
        every { hyperZonePlayer.logLabel } returns OTHER_NAME
        every { player.username } returns OTHER_NAME

        val result = service.loginAs(player, USERNAME, VALID_PASSWORD)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.LOGIN_SUCCESS, result.message)
        verify(exactly = 1) {
            credentialFlow.submitCredential(hyperZonePlayer, match {
                it.channelId == "offline" &&
                    it.credentialId == NORMALIZED_NAME &&
                    it.getBoundUserUUID() == PROFILE.id
            })
        }
        verify(exactly = 1) { credentialFlow.overVerify(hyperZonePlayer) }
    }

    @Test
    fun `login without explicit username gives a hint when current connection name is unregistered`() {
        every { hyperZonePlayer.hasAttachedUser() } returns false
        every { hyperZonePlayer.logLabel } returns OTHER_NAME
        every { player.username } returns OTHER_NAME
        every { userService.getAttachedUser(hyperZonePlayer) } returns null

        val result = service.login(player, VALID_PASSWORD)

        assertFalse(result.success)
        assertEquals(OfflineAuthMessages.loginCurrentNameNotRegistered(OTHER_NAME), result.message)
    }

    @Test
    fun `register falls back to unbound credential flow when profile name conflicts prevent direct creation`() {
        every { userService.getAttachedUser(hyperZonePlayer) } returns null
        // 服务现在通过凭证与 ProfileService 交互；canCreate 返回 false 触发待绑定流程
        every { userService.canCreateUser(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedGameUuid() == null
        }) } returns false

        val credentialSlot = slot<OfflineHyperZoneCredential>()
        every { credentialFlow.submitCredential(hyperZonePlayer, capture(credentialSlot)) } just Runs

        val result = service.register(player, VALID_PASSWORD)
        val savedBeforeBind = repository.getByName(NORMALIZED_NAME)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_BIND_PENDING, result.message)
        assertNull(savedBeforeBind)
        assertTrue(credentialSlot.isCaptured)
        assertNotEquals(NORMALIZED_NAME, credentialSlot.captured.credentialId)
        assertEquals(null, credentialSlot.captured.getBoundUserUUID())
        verify(exactly = 1) {
            credentialFlow.submitCredential(hyperZonePlayer, match {
                it.channelId == "offline" &&
                    it.credentialId != NORMALIZED_NAME &&
                    it.getBoundUserUUID() == null
            })
        }
        verify(exactly = 1) { credentialFlow.overVerify(hyperZonePlayer) }

        insertProfile()
        assertEquals(null, credentialSlot.captured.validateBind(PROFILE.id))
        assertTrue(credentialSlot.captured.bind(PROFILE.id))

        val savedAfterBind = repository.getByProfileId(PROFILE.id)
        assertNotNull(savedAfterBind)
        assertEquals(NORMALIZED_NAME, savedAfterBind!!.name)
        assertEquals(PROFILE.id, savedAfterBind.userUUID)
    }

    @Test
    fun `register pending credential keeps offline uuid suggestion across rename when passthrough is enabled`() {
        enableOfflineUuidPassthrough()

        val offlineUuid = ExtraUuidUtils.getNormalOfflineUUID(NORMALIZED_NAME)
        every { userService.getAttachedUser(hyperZonePlayer) } returns null
        // 服务现在通过凭证与 ProfileService 交互；passthrough 开启时凭证建议 UUID 为离线 UUID
        every { userService.canCreateUser(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedGameUuid() == offlineUuid
        }) } returns false

        val credentialSlot = slot<OfflineHyperZoneCredential>()
        every { credentialFlow.submitCredential(hyperZonePlayer, capture(credentialSlot)) } just Runs

        val result = service.register(player, VALID_PASSWORD)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_BIND_PENDING, result.message)
        assertTrue(credentialSlot.isCaptured)
        assertEquals(offlineUuid, credentialSlot.captured.getSuggestedGameUuid())

        val renamed = credentialSlot.captured.withNewName("Alice_Renamed")

        assertEquals(
            ExtraUuidUtils.getNormalOfflineUUID("alice_renamed"),
            renamed.getSuggestedGameUuid()
        )
        // original captured credential remains immutable
        assertEquals(offlineUuid, credentialSlot.captured.getSuggestedGameUuid())
        verify(exactly = 1) { userService.canCreateUser(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedGameUuid() == offlineUuid
        }) }
    }

    private fun insertProfile() {
        transaction(database) {
            userTable.insert {
                it[id] = PROFILE.id
            }
        }
    }

    private fun enableOfflineUuidPassthrough() {
        AuthOfflineConfigLoader.getConfig().main.passOfflineUuidToProfileResolve = true
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
        private const val NORMALIZED_NAME = "alice"
        private const val OTHER_NAME = "GuestUser"
        private const val VALID_PASSWORD = "SecurePass123"
        private val PROFILE = User(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        )

        private fun hashPassword(password: String): String {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}



