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

package icu.h2l.login.auth.online.service

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.User
import icu.h2l.api.db.table.UserTable
import icu.h2l.api.player.ClientLoginIdentity
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.login.auth.online.config.EntryConfig
import icu.h2l.login.auth.online.db.EntryTableManager
import icu.h2l.login.auth.online.manager.EntryConfigManager
import icu.h2l.login.auth.online.record.YggdrasilAuthResult
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.*

class YggdrasilCredentialServiceTest {
    private lateinit var entryConfigManager: EntryConfigManager
    private lateinit var userService: RecordingProfileService
    private lateinit var service: YggdrasilCredentialService

    @BeforeEach
    fun setUp() {
        val databaseManager = object : HyperZoneDatabaseManager {
            override val tablePrefix: String = ""

            override fun <T> executeTransaction(statement: () -> T): T {
                return statement()
            }
        }
        val entryTableManager = EntryTableManager(
            databaseManager = databaseManager,
            tablePrefix = "",
            userTable = UserTable()
        )
        val proxyServer = interfaceStub(ProxyServer::class.java)

        entryConfigManager = EntryConfigManager(
            dataDirectory = Files.createTempDirectory("auth-yggd-test"),
            proxyServer = proxyServer
        )
        userService = RecordingProfileService()
        val entryDatabaseHelper = icu.h2l.login.auth.online.db.EntryDatabaseHelper(
            databaseManager = databaseManager,
            entryTableManager = entryTableManager
        )

        service = YggdrasilCredentialService(
            entryConfigManager = entryConfigManager,
            userService = userService,
            entryDatabaseHelper = entryDatabaseHelper
        )
    }

    @Test
    fun `ensureCredential honors entry specific yggdrasil uuid passthrough setting`() {
        val enabledUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val disabledUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val enabledProfileId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val disabledProfileId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val enabledHandler = RecordingHyperZonePlayer()
        val disabledHandler = RecordingHyperZonePlayer()

        setConfigs(
            entryConfig("mojang", true),
            entryConfig("elyby", false)
        )
        userService.allowResolve("EnabledUser", enabledUuid, User(enabledProfileId))
        userService.allowResolve("DisabledUser", null, User(disabledProfileId))

        val enabledPreparation = invokeEnsureCredential(
            handler = enabledHandler,
            result = successResult(entryId = "mojang", userName = "EnabledUser", uuid = enabledUuid)
        )
        val disabledPreparation = invokeEnsureCredential(
            handler = disabledHandler,
            result = successResult(entryId = "elyby", userName = "DisabledUser", uuid = disabledUuid)
        )

        assertNull(enabledPreparation.failureReason)
        assertNull(disabledPreparation.failureReason)
        assertEquals(
            listOf(
                ResolveRequest("EnabledUser", enabledUuid),
                ResolveRequest("DisabledUser", null)
            ),
            userService.canCreateCalls
        )
        assertEquals(
            listOf(
                ResolveRequest("EnabledUser", enabledUuid),
                ResolveRequest("DisabledUser", null)
            ),
            userService.createCalls
        )
    }

    @Test
    fun `ensureCredential falls back to passthrough when entry config is missing`() {
        val authenticatedUuid = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val userUUID = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val handler = RecordingHyperZonePlayer()

        setConfigs(entryConfig("other", false))
        userService.allowResolve("FallbackUser", authenticatedUuid, User(userUUID))

        val preparation = invokeEnsureCredential(
            handler = handler,
            result = successResult(entryId = "missing", userName = "FallbackUser", uuid = authenticatedUuid)
        )

        assertNull(preparation.failureReason)
        assertEquals(listOf(ResolveRequest("FallbackUser", authenticatedUuid)), userService.canCreateCalls)
        assertEquals(listOf(ResolveRequest("FallbackUser", authenticatedUuid)), userService.createCalls)
    }

    private fun invokeEnsureCredential(
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult.Success
    ) = service.prepareCredentialForSuccessfulAuth(handler, result)

    private fun successResult(entryId: String, userName: String, uuid: UUID): YggdrasilAuthResult.Success {
        return YggdrasilAuthResult.Success(
            profile = GameProfile(uuid, userName, emptyList()),
            entryId = entryId,
            serverUrl = "https://sessionserver.example/hasJoined"
        )
    }

    private fun setConfigs(vararg configs: EntryConfig) {
        val field = EntryConfigManager::class.java.getDeclaredField("entryConfigs")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val entries = field.get(entryConfigManager) as MutableMap<String, EntryConfig>
        entries.clear()
        configs.forEach { entries[it.id] = it }
    }

    private fun entryConfig(id: String, passThroughUuid: Boolean): EntryConfig {
        return EntryConfig().apply {
            this.id = id
            this.name = id
            this.yggdrasil = EntryConfig.YggdrasilAuthConfig().apply {
                url = "https://sessionserver.example/hasJoined?username={username}&serverId={serverId}"
                passYggdrasilUuidToProfileResolve = passThroughUuid
            }
        }
    }

    private fun <T> interfaceStub(type: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Byte::class.javaPrimitiveType -> 0.toByte()
                Short::class.javaPrimitiveType -> 0.toShort()
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                Char::class.javaPrimitiveType -> 0.toChar()
                else -> null
            }
        } as T
    }

    private data class ResolveRequest(
        val userName: String,
        val uuid: UUID?
    )

    private class RecordingProfileService : HyperZoneUserService {
        private val allowedResolutions = mutableMapOf<ResolveRequest, User>()
        val canCreateCalls = mutableListOf<ResolveRequest>()
        val createCalls = mutableListOf<ResolveRequest>()

        fun allowResolve(userName: String, uuid: UUID?, profile: User) {
            allowedResolutions[ResolveRequest(userName, uuid)] = profile
        }

        override fun getUser(userUUID: UUID): User? = null

        override fun getUserByName(name: String) = null

        override fun getUserByGameUuid(playerUuid: UUID): User? = null

        override fun getAttachedUser(player: HyperZonePlayer): User? = null

        override fun attachUser(player: HyperZonePlayer, userUUID: UUID): User? = null

        override fun canCreateUser(credential: HyperZoneCredential): Boolean {
            val request = ResolveRequest(
                credential.getRegistrationName() ?: error("No registration name"),
                credential.getSuggestedGameUuid()
            )
            canCreateCalls += request
            return allowedResolutions.containsKey(request)
        }

        override fun createUser(credential: HyperZoneCredential): User {
            val request = ResolveRequest(
                credential.getRegistrationName() ?: error("No registration name"),
                credential.getSuggestedGameUuid()
            )
            createCalls += request
            return allowedResolutions[request]
                ?: throw IllegalStateException("Unexpected resolve request: $request")
        }

        override fun resolveAuthenticatedUser(player: HyperZonePlayer): User? = null

        override fun bindSubmittedCredentials(player: HyperZonePlayer, userUUID: UUID): User {
            throw UnsupportedOperationException("Not used in this test")
        }
    }

    private class RecordingHyperZonePlayer : HyperZonePlayer {
        override val loginIdentity = ClientLoginIdentity("Recording", UUID.randomUUID())
        override val userUUID: UUID? = null
        private val submittedCredentials = mutableListOf<HyperZoneCredential>()
        private val temporaryProfile: GameProfile = GameProfile(UUID.randomUUID(), "temp", emptyList())

        override val isOnlinePlayer: Boolean = false

        override fun hasAttachedUser(): Boolean = false

        override fun getSubmittedCredentials(): List<HyperZoneCredential> = submittedCredentials.toList()

        override fun canBind(): Boolean = true


        override fun resetVerify() = Unit

        override fun sendMessage(message: Component) = Unit

        override fun getTemporaryGameProfile(): GameProfile = temporaryProfile

        override fun getAttachedGameProfile(): GameProfile = temporaryProfile
    }
}
