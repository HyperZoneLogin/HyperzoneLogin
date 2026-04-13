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

import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.Profile
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.auth.floodgate.config.FloodgateAuthConfig
import icu.h2l.login.auth.floodgate.credential.FloodgateHyperZoneCredential
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
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
    private lateinit var service: FloodgateAuthService
    private lateinit var channel: Channel

    @BeforeEach
    fun setUp() {
        api = mockk(relaxed = true)
        playerAccessor = mockk()
        floodgateApiHolder = FakeFloodgateApiHolder()
        profileService = mockk()
        sessionHolder = FloodgateSessionHolder()
        channel = mockk()

        every { api.hyperZonePlayers } returns playerAccessor

        service = FloodgateAuthService(
            api = api,
            floodgateApiHolder = floodgateApiHolder,
            sessionHolder = sessionHolder,
            profileService = profileService
        )
    }

    @Test
    fun `acceptInitialProfile returns NotFloodgate when api does not trust uuid`() {
        val userUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val result = service.acceptInitialProfile(channel, "BedrockUser", userUuid)

        assertSame(FloodgateAuthService.VerifyResult.NotFloodgate, result)
        assertNull(sessionHolder.get(channel))
        verify(exactly = 0) { playerAccessor.create(any(), any(), any(), any()) }
    }

    @Test
    fun `acceptInitialProfile strips default floodgate prefix by default`() {
        val userUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        val temporaryProfile = slot<GameProfile>()
        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        every { hyperPlayer.setTemporaryGameProfile(capture(temporaryProfile)) } just runs
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } returns hyperPlayer

        val result = service.acceptInitialProfile(channel, ".BedrockUser", userUuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        val remembered = sessionHolder.get(channel)
        assertNotNull(remembered)
        assertEquals("BedrockUser", remembered!!.userName)
        assertEquals(userUuid, remembered.userUUID)
        assertTrue(temporaryProfile.isCaptured)
        assertTrue(temporaryProfile.captured.name.startsWith(RemapUtils.EXPECTED_NAME_PREFIX))
        assertEquals(
            RemapUtils.genUUID(temporaryProfile.captured.name, RemapUtils.REMAP_PREFIX),
            temporaryProfile.captured.id
        )
        verify(exactly = 1) { playerAccessor.create(channel, "BedrockUser", userUuid, any()) }
        verify(exactly = 1) { hyperPlayer.setTemporaryGameProfile(any()) }
    }

    @Test
    fun `acceptInitialProfile reuses existing login player and assigns temporary profile on duplicate create`() {
        val userUuid = UUID.fromString("12121212-1212-1212-1212-121212121212")
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        val temporaryProfile = slot<GameProfile>()
        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        every { hyperPlayer.setTemporaryGameProfile(capture(temporaryProfile)) } just runs
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } throws IllegalStateException("重复创建 HyperZonePlayer")
        every { playerAccessor.getByChannel(channel) } returns hyperPlayer

        val result = service.acceptInitialProfile(channel, ".BedrockUser", userUuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        assertEquals("BedrockUser", sessionHolder.get(channel)?.userName)
        assertTrue(temporaryProfile.isCaptured)
        assertTrue(temporaryProfile.captured.name.startsWith(RemapUtils.EXPECTED_NAME_PREFIX))
        assertEquals(
            RemapUtils.genUUID(temporaryProfile.captured.name, RemapUtils.REMAP_PREFIX),
            temporaryProfile.captured.id
        )
        verify(exactly = 1) { playerAccessor.create(channel, "BedrockUser", userUuid, any()) }
        verify(exactly = 1) { playerAccessor.getByChannel(channel) }
        verify(exactly = 1) { hyperPlayer.setTemporaryGameProfile(any()) }
    }

    @Test
    fun `acceptInitialProfile keeps prefix when strip config is disabled`() {
        val userUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        val disabledSessionHolder = FloodgateSessionHolder()
        val disabledConfig = FloodgateAuthConfig().apply { stripUsernamePrefix = false }
        val disabledService = FloodgateAuthService(
            api = api,
            floodgateApiHolder = floodgateApiHolder,
            sessionHolder = disabledSessionHolder,
            config = disabledConfig,
            profileService = profileService
        )
        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, ".BedrockUser", userUuid, any()) } returns hyperPlayer

        val result = disabledService.acceptInitialProfile(channel, ".BedrockUser", userUuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        val remembered = disabledSessionHolder.get(channel)
        assertNotNull(remembered)
        assertEquals(".BedrockUser", remembered!!.userName)
        verify(exactly = 1) { playerAccessor.create(channel, ".BedrockUser", userUuid, any()) }
    }

    @Test
    fun `complete submits floodgate credential verifies player and clears normalized session`() {
        val userUuid = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val profileId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val resolvedProfile = Profile(profileId, "BedrockUser", userUuid)
        val submittedCredentials = mutableListOf<HyperZoneCredential>()
        val hyperPlayer = mockk<HyperZonePlayer>()

        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } returns hyperPlayer
        every { hyperPlayer.setTemporaryGameProfile(any()) } just runs
        every { hyperPlayer.clientOriginalName } returns "BedrockUser"
        every { hyperPlayer.getSubmittedCredentials() } answers { submittedCredentials.toList() }
        every { hyperPlayer.submitCredential(any()) } answers {
            submittedCredentials += firstArg<HyperZoneCredential>()
        }
        every { hyperPlayer.overVerify() } just runs
        every { profileService.getAttachedProfile(hyperPlayer) } returns null
        every { profileService.resolveOrCreateProfile(hyperPlayer, "BedrockUser", userUuid) } returns resolvedProfile

        val acceptResult = service.acceptInitialProfile(channel, ".BedrockUser", userUuid)

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
        verify(exactly = 1) { profileService.resolveOrCreateProfile(hyperPlayer, "BedrockUser", userUuid) }
    }

    @Test
    fun `complete passes null to profile resolve when floodgate uuid passthrough is disabled`() {
        val userUuid = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val profileId = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val resolvedProfile = Profile(profileId, "BedrockUser", userUuid)
        val submittedCredentials = mutableListOf<HyperZoneCredential>()
        val hyperPlayer = mockk<HyperZonePlayer>()
        val disabledConfig = FloodgateAuthConfig().apply { passFloodgateUuidToProfileResolve = false }
        val disabledService = FloodgateAuthService(
            api = api,
            floodgateApiHolder = floodgateApiHolder,
            sessionHolder = sessionHolder,
            config = disabledConfig,
            profileService = profileService
        )

        floodgateApiHolder.configuredPlayerPrefix = "."
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } returns hyperPlayer
        every { hyperPlayer.setTemporaryGameProfile(any()) } just runs
        every { hyperPlayer.clientOriginalName } returns "BedrockUser"
        every { hyperPlayer.getSubmittedCredentials() } answers { submittedCredentials.toList() }
        every { hyperPlayer.submitCredential(any()) } answers {
            submittedCredentials += firstArg<HyperZoneCredential>()
        }
        every { hyperPlayer.overVerify() } just runs
        every { profileService.getAttachedProfile(hyperPlayer) } returns null
        every { profileService.resolveOrCreateProfile(hyperPlayer, "BedrockUser", null) } returns resolvedProfile

        val acceptResult = disabledService.acceptInitialProfile(channel, ".BedrockUser", userUuid)

        val result = disabledService.complete(channel, hyperPlayer)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, acceptResult)
        assertTrue(result.handled)
        assertTrue(result.passed)
        verify(exactly = 1) { profileService.resolveOrCreateProfile(hyperPlayer, "BedrockUser", null) }
        verify(exactly = 0) { profileService.resolveOrCreateProfile(hyperPlayer, "BedrockUser", userUuid) }
    }

    @Test
    fun `acceptInitialProfile strips custom floodgate prefix returned by api`() {
        val userUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        floodgateApiHolder.configuredPlayerPrefix = "fg_"
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, "BedrockUser", userUuid, any()) } returns hyperPlayer

        val result = service.acceptInitialProfile(channel, "fg_BedrockUser", userUuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        assertEquals("BedrockUser", sessionHolder.get(channel)?.userName)
        verify(exactly = 1) { playerAccessor.create(channel, "BedrockUser", userUuid, any()) }
    }

    @Test
    fun `acceptInitialProfile keeps username unchanged when floodgate api prefix is blank`() {
        val userUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        floodgateApiHolder.configuredPlayerPrefix = ""
        floodgateApiHolder.trustedUuids += userUuid
        every { playerAccessor.create(channel, ".BedrockUser", userUuid, any()) } returns hyperPlayer

        val result = service.acceptInitialProfile(channel, ".BedrockUser", userUuid)

        assertSame(FloodgateAuthService.VerifyResult.Accepted, result)
        assertEquals(".BedrockUser", sessionHolder.get(channel)?.userName)
        verify(exactly = 1) { playerAccessor.create(channel, ".BedrockUser", userUuid, any()) }
    }

    @Test
    fun `complete returns unhandled when neither floodgate session nor credential exists`() {
        val hyperPlayer = mockk<HyperZonePlayer>()
        every { hyperPlayer.getSubmittedCredentials() } returns emptyList()

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
}



