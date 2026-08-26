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

package icu.h2l.login.auth.floodgate.service

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.Profile
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.login.auth.floodgate.config.FloodgateAuthConfig
import icu.h2l.login.auth.floodgate.credential.FloodgateHyperZoneCredential
import icu.h2l.login.auth.floodgate.api.db.FloodgateAuthEntry
import icu.h2l.login.auth.floodgate.db.FloodgateAuthRepository
import io.mockk.*
import io.netty.channel.Channel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class FloodgateAuthServiceTest {
    private lateinit var api: HyperZoneApi
    private lateinit var playerAccessor: HyperZonePlayerAccessor
    private lateinit var floodgateApiHolder: FakeFloodgateApiHolder
    private lateinit var profileService: HyperZoneProfileService
    private lateinit var sessionHolder: FloodgateSessionHolder
    private lateinit var repository: FloodgateAuthRepository
    private lateinit var service: FloodgateAuthService
    private lateinit var channel: Channel

    @BeforeEach
    fun setUp() {
        api = mockk(relaxed = true)
        playerAccessor = mockk()
        floodgateApiHolder = FakeFloodgateApiHolder()
        profileService = mockk()
        sessionHolder = FloodgateSessionHolder()
        repository = FakeFloodgateAuthRepository()
        channel = mockk()

        every { api.hyperZonePlayers } returns playerAccessor

        service = FloodgateAuthService(
            api = api,
            floodgateApiHolder = floodgateApiHolder,
            sessionHolder = sessionHolder,
            repository = repository,
            profileService = profileService
        )
    }

    @Test
    fun `acceptInitialProfile returns NotFloodgate when api does not trust uuid`() {
        val userUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val xuid = 1111111111111111L

        val result = service.acceptInitialProfile(channel, "BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.NotFloodgate, result)
        assertNull(sessionHolder.get(channel))
        verify(exactly = 0) { playerAccessor.create(any(), any(), any(), any()) }
    }

    @Test
    fun `acceptInitialProfile strips default floodgate prefix by default`() {
        val userUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val xuid = 2222222222222222L
        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid

        val result = service.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        val remembered = sessionHolder.get(channel)
        assertNotNull(remembered)
        assertEquals("BedrockUser", remembered!!.userName)
        assertEquals(userUuid, remembered.userUUID)
        assertEquals(xuid, remembered.xuid)
        verify(exactly = 0) { playerAccessor.create(any(), any(), any(), any()) }
    }

    @Test
    fun `acceptInitialProfile keeps prefix when strip config is disabled`() {
        val userUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val xuid = 1234567890123456L
        val disabledSessionHolder = FloodgateSessionHolder()
        val disabledConfig = FloodgateAuthConfig().apply { stripUsernamePrefix = false }
        val disabledService = FloodgateAuthService(
            api = api,
            floodgateApiHolder = floodgateApiHolder,
            sessionHolder = disabledSessionHolder,
            repository = repository,
            config = disabledConfig,
            profileService = profileService
        )
        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid

        val result = disabledService.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        val remembered = disabledSessionHolder.get(channel)
        assertNotNull(remembered)
        assertEquals(".BedrockUser", remembered!!.userName)
        verify(exactly = 0) { playerAccessor.create(any(), any(), any(), any()) }
    }

    @Test
    fun `complete submits floodgate credential verifies player and clears normalized session`() {
        val userUuid = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val xuid = 3333333333333333L
        val profileId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val resolvedProfile = Profile(profileId, "BedrockUser", userUuid)
        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        // 服务现在通过凭证与 ProfileService 交互；按凭证渠道参数匹配模拟行为
        every { profileService.canCreate(match<HyperZoneCredential> {
            it.getRegistrationName() == "BedrockUser" && it.getSuggestedProfileCreateUuid() == userUuid
        }) } returns true
        every { profileService.create(match<HyperZoneCredential> {
            it.getRegistrationName() == "BedrockUser" && it.getSuggestedProfileCreateUuid() == userUuid
        }) } returns resolvedProfile

        val acceptResult = service.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        val result = service.complete(channel, "BedrockUser")

        assertSame(FloodgateAuthService.VerifyResult.Accepted, acceptResult)
        assertTrue(result.handled)
        assertTrue(result.passed)
        assertNull(result.userMessage)
        assertNull(sessionHolder.get(channel))
        val credential = result.credential as FloodgateHyperZoneCredential
        assertEquals(profileId, credential.getBoundProfileId())
        assertTrue(credential.matches(userUuid))
        assertEquals(profileId, result.profileIdHint)
        assertEquals(profileId, repository.findProfileIdByXuid(xuid))
        verify(exactly = 1) { profileService.canCreate(match<HyperZoneCredential> {
            it.getRegistrationName() == "BedrockUser" && it.getSuggestedProfileCreateUuid() == userUuid
        }) }
        verify(exactly = 1) { profileService.create(match<HyperZoneCredential> {
            it.getRegistrationName() == "BedrockUser" && it.getSuggestedProfileCreateUuid() == userUuid
        }) }
    }

    @Test
    fun `complete passes null to profile resolve when floodgate uuid passthrough is disabled`() {
        val userUuid = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val xuid = 5555555555555555L
        val profileId = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val resolvedProfile = Profile(profileId, "BedrockUser", userUuid)
        val disabledConfig = FloodgateAuthConfig().apply { passFloodgateUuidToProfileResolve = false }
        val disabledService = FloodgateAuthService(
            api = api,
            floodgateApiHolder = floodgateApiHolder,
            sessionHolder = sessionHolder,
            repository = repository,
            config = disabledConfig,
            profileService = profileService
        )

        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        // 服务现在通过凭证与 ProfileService 交互；uuid passthrough 关闭时凭证建议 UUID 应为 null
        every { profileService.canCreate(match<HyperZoneCredential> {
            it.getRegistrationName() == "BedrockUser" && it.getSuggestedProfileCreateUuid() == null
        }) } returns true
        every { profileService.create(match<HyperZoneCredential> {
            it.getRegistrationName() == "BedrockUser" && it.getSuggestedProfileCreateUuid() == null
        }) } returns resolvedProfile

        val acceptResult = disabledService.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        val result = disabledService.complete(channel, "BedrockUser")

        assertSame(FloodgateAuthService.VerifyResult.Accepted, acceptResult)
        assertTrue(result.handled)
        assertTrue(result.passed)
        assertEquals(profileId, repository.findProfileIdByXuid(xuid))
        verify(exactly = 1) { profileService.canCreate(match<HyperZoneCredential> {
            it.getSuggestedProfileCreateUuid() == null
        }) }
        verify(exactly = 1) { profileService.create(match<HyperZoneCredential> {
            it.getSuggestedProfileCreateUuid() == null
        }) }
        verify(exactly = 0) { profileService.create(match<HyperZoneCredential> {
            it.getSuggestedProfileCreateUuid() == userUuid
        }) }
    }

    @Test
    fun `complete reuses repository binding before creating profile`() {
        val userUuid = UUID.fromString("77777777-7777-7777-7777-777777777777")
        val xuid = 7777777777777777L
        val profileId = UUID.fromString("88888888-8888-8888-8888-888888888888")
        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        repository.createOrUpdate("oldname", xuid, profileId)

        val acceptResult = service.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        val result = service.complete(channel, "BedrockUser")

        assertSame(FloodgateAuthService.VerifyResult.Accepted, acceptResult)
        assertTrue(result.handled)
        assertTrue(result.passed)
        assertEquals(profileId, (result.credential as FloodgateHyperZoneCredential).getBoundProfileId())
        assertEquals(profileId, repository.findProfileIdByXuid(xuid))
        assertEquals("bedrockuser", repository.getByXuid(xuid)?.name)
        verify(exactly = 0) { profileService.create(any<HyperZoneCredential>()) }
    }

    @Test
    fun `acceptInitialProfile strips custom floodgate prefix returned by api`() {
        val userUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val xuid = 8888888888888888L
        floodgateApiHolder.configuredPlayerPrefix = "fg_"
        floodgateApiHolder.trustedUuids += userUuid

        val result = service.acceptInitialProfile(channel, "fg_BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        assertEquals("BedrockUser", sessionHolder.get(channel)?.userName)
        verify(exactly = 0) { playerAccessor.create(any(), any(), any(), any()) }
    }

    @Test
    fun `acceptInitialProfile keeps username unchanged when floodgate api prefix is blank`() {
        val userUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val xuid = 9999999999999999L
        floodgateApiHolder.configuredPlayerPrefix = ""
        floodgateApiHolder.trustedUuids += userUuid

        val result = service.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        assertEquals(".BedrockUser", sessionHolder.get(channel)?.userName)
        verify(exactly = 0) { playerAccessor.create(any(), any(), any(), any()) }
    }

    @Test
    fun `complete returns unhandled when neither floodgate session nor credential exists`() {
        val result = service.complete(channel, "BedrockUser")

        assertFalse(result.handled)
        assertFalse(result.passed)
    }


    private class FakeFloodgateApiHolder : FloodgateApiHolder(mockk(relaxed = true)) {
        val trustedUuids = mutableSetOf<UUID>()
        var configuredPlayerPrefix: String = "."

        override fun isFloodgatePlayer(uuid: UUID): Boolean {
            return uuid in trustedUuids
        }

        override fun getPlayerPrefix(): String {
            return configuredPlayerPrefix
        }
    }

    private class FakeFloodgateAuthRepository : FloodgateAuthRepository(mockk(relaxed = true), mockk(relaxed = true)) {
        private val entriesByXuid = linkedMapOf<Long, FloodgateAuthEntry>()
        private var nextId = 1

        override fun getByXuid(xuid: Long): FloodgateAuthEntry? {
            return entriesByXuid[xuid]
        }

        override fun getByProfileId(profileId: UUID): FloodgateAuthEntry? {
            return entriesByXuid.values.firstOrNull { it.profileId == profileId }
        }

        override fun findProfileIdByXuid(xuid: Long): UUID? {
            return entriesByXuid[xuid]?.profileId
        }

        override fun createOrUpdate(name: String, xuid: Long, profileId: UUID): Boolean {
            val normalizedName = name.lowercase()
            val existingByXuid = entriesByXuid[xuid]
            val existingByProfileId = getByProfileId(profileId)
            if (existingByXuid != null && existingByXuid.profileId != profileId) {
                return false
            }
            if (existingByProfileId != null && existingByProfileId.xuid != xuid) {
                return false
            }

            val id = existingByXuid?.id ?: existingByProfileId?.id ?: nextId++
            entriesByXuid[xuid] = FloodgateAuthEntry(id, normalizedName, xuid, profileId)
            return true
        }

        override fun updateEntryName(xuid: Long, newName: String): Boolean {
            val existing = entriesByXuid[xuid] ?: return false
            entriesByXuid[xuid] = existing.copy(name = newName.lowercase())
            return true
        }
    }
}


