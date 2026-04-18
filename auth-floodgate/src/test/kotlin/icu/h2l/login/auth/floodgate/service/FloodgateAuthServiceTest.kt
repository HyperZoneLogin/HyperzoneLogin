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
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.login.auth.floodgate.config.FloodgateAuthConfig
import icu.h2l.login.auth.floodgate.credential.FloodgateHyperZoneCredential
import icu.h2l.login.auth.floodgate.db.FloodgateAuthEntry
import icu.h2l.login.auth.floodgate.db.FloodgateAuthRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.netty.channel.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

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
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } returns hyperPlayer

        val result = service.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        val remembered = sessionHolder.get(channel)
        assertNotNull(remembered)
        assertEquals("BedrockUser", remembered!!.userName)
        assertEquals(userUuid, remembered.userUUID)
        assertEquals(xuid, remembered.xuid)
        verify(exactly = 1) { playerAccessor.create(channel, "BedrockUser", userUuid, any()) }
    }

    @Test
    fun `acceptInitialProfile reuses existing login player on duplicate create`() {
        val userUuid = UUID.fromString("12121212-1212-1212-1212-121212121212")
        val xuid = 1212121212121212L
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } throws IllegalStateException("重复创建 HyperZonePlayer")
        every { playerAccessor.getByChannel(channel) } returns hyperPlayer

        val result = service.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        assertEquals("BedrockUser", sessionHolder.get(channel)?.userName)
        verify(exactly = 1) { playerAccessor.create(channel, "BedrockUser", userUuid, any()) }
        verify(exactly = 1) { playerAccessor.getByChannel(channel) }
    }

    @Test
    fun `acceptInitialProfile keeps prefix when strip config is disabled`() {
        val userUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val xuid = 1234567890123456L
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
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
        every { playerAccessor.create(channel, ".BedrockUser", userUuid, any()) } returns hyperPlayer

        val result = disabledService.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        val remembered = disabledSessionHolder.get(channel)
        assertNotNull(remembered)
        assertEquals(".BedrockUser", remembered!!.userName)
        verify(exactly = 1) { playerAccessor.create(channel, ".BedrockUser", userUuid, any()) }
    }

    @Test
    fun `complete submits floodgate credential verifies player and clears normalized session`() {
        val userUuid = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val xuid = 3333333333333333L
        val profileId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val resolvedProfile = Profile(profileId, "BedrockUser", userUuid)
        val submittedCredentials = mutableListOf<HyperZoneCredential>()
        val hyperPlayer = mockk<HyperZonePlayer>()

        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } returns hyperPlayer
        every { hyperPlayer.clientOriginalName } returns "BedrockUser"
        every { hyperPlayer.registrationName } returns "BedrockUser"
        every { hyperPlayer.isInWaitingArea() } returns true
        every { hyperPlayer.isVerified() } returns false
        every { hyperPlayer.getSubmittedCredentials() } answers { submittedCredentials.toList() }
        every { hyperPlayer.submitCredential(any()) } answers {
            submittedCredentials += firstArg<HyperZoneCredential>()
        }
        every { hyperPlayer.overVerify() } just runs
        every { profileService.getAttachedProfile(hyperPlayer) } returns null
        every { profileService.canCreate("BedrockUser", userUuid) } returns true
        every { profileService.create("BedrockUser", userUuid) } returns resolvedProfile

        val acceptResult = service.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        val result = service.complete(channel, hyperPlayer)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, acceptResult)
        assertTrue(result.handled)
        assertTrue(result.passed)
        assertNull(result.userMessage)
        assertNull(sessionHolder.get(channel))
        assertEquals(1, submittedCredentials.size)
        val credential = submittedCredentials.single() as FloodgateHyperZoneCredential
        assertEquals(profileId, credential.getBoundProfileId())
        assertTrue(credential.matches(userUuid))
        verify(exactly = 1) { hyperPlayer.overVerify() }
        assertEquals(profileId, repository.findProfileIdByXuid(xuid))
        verify(exactly = 1) { profileService.canCreate("BedrockUser", userUuid) }
        verify(exactly = 1) { profileService.create("BedrockUser", userUuid) }
    }

    @Test
    fun `complete passes null to profile resolve when floodgate uuid passthrough is disabled`() {
        val userUuid = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val xuid = 5555555555555555L
        val profileId = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val resolvedProfile = Profile(profileId, "BedrockUser", userUuid)
        val submittedCredentials = mutableListOf<HyperZoneCredential>()
        val hyperPlayer = mockk<HyperZonePlayer>()
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
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } returns hyperPlayer
        every { hyperPlayer.clientOriginalName } returns "BedrockUser"
        every { hyperPlayer.registrationName } returns "BedrockUser"
        every { hyperPlayer.isInWaitingArea() } returns true
        every { hyperPlayer.isVerified() } returns false
        every { hyperPlayer.getSubmittedCredentials() } answers { submittedCredentials.toList() }
        every { hyperPlayer.submitCredential(any()) } answers {
            submittedCredentials += firstArg<HyperZoneCredential>()
        }
        every { hyperPlayer.overVerify() } just runs
        every { profileService.getAttachedProfile(hyperPlayer) } returns null
        every { profileService.canCreate("BedrockUser", null) } returns true
        every { profileService.create("BedrockUser", null) } returns resolvedProfile

        val acceptResult = disabledService.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        val result = disabledService.complete(channel, hyperPlayer)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, acceptResult)
        assertTrue(result.handled)
        assertTrue(result.passed)
        assertEquals(profileId, repository.findProfileIdByXuid(xuid))
        verify(exactly = 1) { profileService.canCreate("BedrockUser", null) }
        verify(exactly = 1) { profileService.create("BedrockUser", null) }
        verify(exactly = 0) { profileService.create("BedrockUser", userUuid) }
    }

    @Test
    fun `complete reuses repository binding before creating profile`() {
        val userUuid = UUID.fromString("77777777-7777-7777-7777-777777777777")
        val xuid = 7777777777777777L
        val profileId = UUID.fromString("88888888-8888-8888-8888-888888888888")
        val submittedCredentials = mutableListOf<HyperZoneCredential>()
        val hyperPlayer = mockk<HyperZonePlayer>()

        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } returns hyperPlayer
        every { hyperPlayer.clientOriginalName } returns "BedrockUser"
        every { hyperPlayer.registrationName } returns "BedrockUser"
        every { hyperPlayer.isInWaitingArea() } returns true
        every { hyperPlayer.isVerified() } returns false
        every { hyperPlayer.getSubmittedCredentials() } answers { submittedCredentials.toList() }
        every { hyperPlayer.submitCredential(any()) } answers {
            submittedCredentials += firstArg<HyperZoneCredential>()
        }
        every { hyperPlayer.overVerify() } just runs
        every { profileService.getAttachedProfile(hyperPlayer) } returns null
        repository.createOrUpdate("oldname", xuid, profileId)

        val acceptResult = service.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        val result = service.complete(channel, hyperPlayer)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, acceptResult)
        assertTrue(result.handled)
        assertTrue(result.passed)
        assertEquals(profileId, (submittedCredentials.single() as FloodgateHyperZoneCredential).getBoundProfileId())
        assertEquals(profileId, repository.findProfileIdByXuid(xuid))
        assertEquals("bedrockuser", repository.getByXuid(xuid)?.name)
        verify(exactly = 0) { profileService.create(any(), any()) }
    }

    @Test
    fun `acceptInitialProfile strips custom floodgate prefix returned by api`() {
        val userUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val xuid = 8888888888888888L
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        floodgateApiHolder.configuredPlayerPrefix = "fg_"
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } returns hyperPlayer

        val result = service.acceptInitialProfile(channel, "fg_BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        assertEquals("BedrockUser", sessionHolder.get(channel)?.userName)
        verify(exactly = 1) { playerAccessor.create(channel, "BedrockUser", userUuid, any()) }
    }

    @Test
    fun `acceptInitialProfile keeps username unchanged when floodgate api prefix is blank`() {
        val userUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val xuid = 9999999999999999L
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        floodgateApiHolder.configuredPlayerPrefix = ""
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, ".BedrockUser", userUuid, any()) } returns hyperPlayer

        val result = service.acceptInitialProfile(channel, ".BedrockUser", userUuid, xuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        assertEquals(".BedrockUser", sessionHolder.get(channel)?.userName)
        verify(exactly = 1) { playerAccessor.create(channel, ".BedrockUser", userUuid, any()) }
    }

    @Test
    fun `complete returns unhandled when neither floodgate session nor credential exists`() {
        val hyperPlayer = mockk<HyperZonePlayer>()
        every { hyperPlayer.clientOriginalName } returns "BedrockUser"
        every { hyperPlayer.isInWaitingArea() } returns true
        every { hyperPlayer.isVerified() } returns false
        every { hyperPlayer.getSubmittedCredentials() } returns emptyList()
        every { profileService.getAttachedProfile(hyperPlayer) } returns null

        val result = service.complete(channel, hyperPlayer)

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



