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

package icu.h2l.login.auth.online

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.Profile
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.login.auth.online.config.entry.EntryConfig
import icu.h2l.login.auth.online.db.EntryTableManager
import icu.h2l.login.auth.online.manager.EntryConfigManager
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID

class YggdrasilAuthModuleTest {
    private lateinit var entryConfigManager: EntryConfigManager
    private lateinit var profileService: RecordingProfileService
    private lateinit var module: YggdrasilAuthModule

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
            profileTable = ProfileTable()
        )
        val proxyServer = interfaceStub(ProxyServer::class.java)

        entryConfigManager = EntryConfigManager(
            dataDirectory = Files.createTempDirectory("auth-yggd-test"),
            proxyServer = proxyServer
        )
        profileService = RecordingProfileService()

        module = YggdrasilAuthModule(
            proxy = proxyServer,
            entryConfigManager = entryConfigManager,
            databaseManager = databaseManager,
            entryTableManager = entryTableManager,
            playerAccessor = interfaceStub(HyperZonePlayerAccessor::class.java),
            profileService = profileService
        )
    }

    @Test
    fun `ensureCredential honors entry specific yggdrasil uuid passthrough setting`() {
        val enabledUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val disabledUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val enabledProfileId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val disabledProfileId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val enabledHandler = RecordingHyperZonePlayer("EnabledUser")
        val disabledHandler = RecordingHyperZonePlayer("DisabledUser")

        setConfigs(
            entryConfig("mojang", true),
            entryConfig("elyby", false)
        )
        profileService.allowResolve("EnabledUser", enabledUuid, Profile(enabledProfileId, "EnabledUser", enabledUuid))
        profileService.allowResolve("DisabledUser", null, Profile(disabledProfileId, "DisabledUser", disabledUuid))

        val enabledError = invokeEnsureCredential(
            handler = enabledHandler,
            result = successResult(entryId = "mojang", userName = "EnabledUser", uuid = enabledUuid)
        )
        val disabledError = invokeEnsureCredential(
            handler = disabledHandler,
            result = successResult(entryId = "elyby", userName = "DisabledUser", uuid = disabledUuid)
        )

        assertNull(enabledError)
        assertNull(disabledError)
        assertEquals(
            listOf(
                ResolveRequest("EnabledUser", enabledUuid),
                ResolveRequest("DisabledUser", null)
            ),
            profileService.canResolveCalls
        )
        assertEquals(
            listOf(
                ResolveRequest("EnabledUser", enabledUuid),
                ResolveRequest("DisabledUser", null)
            ),
            profileService.resolveCalls
        )
    }

    @Test
    fun `ensureCredential falls back to passthrough when entry config is missing`() {
        val authenticatedUuid = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val profileId = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val handler = RecordingHyperZonePlayer("FallbackUser")

        setConfigs(entryConfig("other", false))
        profileService.allowResolve("FallbackUser", authenticatedUuid, Profile(profileId, "FallbackUser", authenticatedUuid))

        val error = invokeEnsureCredential(
            handler = handler,
            result = successResult(entryId = "missing", userName = "FallbackUser", uuid = authenticatedUuid)
        )

        assertNull(error)
        assertEquals(listOf(ResolveRequest("FallbackUser", authenticatedUuid)), profileService.canResolveCalls)
        assertEquals(listOf(ResolveRequest("FallbackUser", authenticatedUuid)), profileService.resolveCalls)
    }

    private fun invokeEnsureCredential(
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult.Success
    ): String? {
        val method = YggdrasilAuthModule::class.java.getDeclaredMethod(
            "ensureCredentialForSuccessfulAuth",
            HyperZonePlayer::class.java,
            YggdrasilAuthResult.Success::class.java
        )
        method.isAccessible = true
        return method.invoke(module, handler, result) as String?
    }

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

    private class RecordingProfileService : HyperZoneProfileService {
        private val allowedResolutions = mutableMapOf<ResolveRequest, Profile>()
        val canResolveCalls = mutableListOf<ResolveRequest>()
        val resolveCalls = mutableListOf<ResolveRequest>()

        fun allowResolve(userName: String, uuid: UUID?, profile: Profile) {
            allowedResolutions[ResolveRequest(userName, uuid)] = profile
        }

        override fun getProfile(profileId: UUID): Profile? = null

        override fun getAttachedProfile(player: HyperZonePlayer): Profile? = null

        override fun attachProfile(player: HyperZonePlayer, profileId: UUID): Profile? = null

        override fun canResolveOrCreateProfile(userName: String, uuid: UUID?): Boolean {
            val request = ResolveRequest(userName, uuid)
            canResolveCalls += request
            return allowedResolutions.containsKey(request)
        }

        override fun tryResolveOrCreateProfile(userName: String, uuid: UUID?) =
            throw UnsupportedOperationException("Not used in this test")

        override fun resolveOrCreateProfile(player: HyperZonePlayer, userName: String?, uuid: UUID?): Profile {
            val resolvedName = userName ?: player.clientOriginalName
            val request = ResolveRequest(resolvedName, uuid)
            resolveCalls += request
            return allowedResolutions[request]
                ?: throw IllegalStateException("Unexpected resolve request: $request")
        }

        override fun attachVerifiedCredentialProfile(player: HyperZonePlayer): Profile? = null

        override fun bindSubmittedCredentials(player: HyperZonePlayer, profileId: UUID): Profile {
            throw UnsupportedOperationException("Not used in this test")
        }
    }

    private class RecordingHyperZonePlayer(
        override val clientOriginalName: String,
        override val clientOriginalUUID: UUID = UUID.randomUUID()
    ) : HyperZonePlayer {
        private val submittedCredentials = mutableListOf<HyperZoneCredential>()
        private var temporaryProfile: GameProfile = GameProfile(UUID.randomUUID(), "temp", emptyList())

        override val isOnlinePlayer: Boolean = false

        override fun hasAttachedProfile(): Boolean = false

        override fun submitCredential(credential: HyperZoneCredential) {
            submittedCredentials += credential
        }

        override fun getSubmittedCredentials(): List<HyperZoneCredential> = submittedCredentials.toList()

        override fun isVerified(): Boolean = false

        override fun canBind(): Boolean = true

        override fun overVerify() = Unit

        override fun resetVerify() = Unit

        override fun sendMessage(message: Component) = Unit

        override fun getTemporaryGameProfile(): GameProfile = temporaryProfile

        override fun getAttachedGameProfile(): GameProfile = temporaryProfile

        override fun setTemporaryGameProfile(profile: GameProfile?) {
            temporaryProfile = profile ?: temporaryProfile
        }
    }
}



