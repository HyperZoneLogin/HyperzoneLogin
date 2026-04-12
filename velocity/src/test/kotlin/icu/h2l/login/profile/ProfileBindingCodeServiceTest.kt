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

package icu.h2l.login.profile

import icu.h2l.api.db.Profile
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.login.database.BindingCodeStore
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class ProfileBindingCodeServiceTest {
    private lateinit var repository: FakeBindingCodeStore
    private lateinit var profileService: FakeProfileService
    private lateinit var bindingCodeService: ProfileBindingCodeService
    private lateinit var hyperZonePlayer: FakeHyperZonePlayer

    @BeforeEach
    fun setUp() {
        repository = FakeBindingCodeStore()
        profileService = FakeProfileService()
        hyperZonePlayer = FakeHyperZonePlayer()
        bindingCodeService = ProfileBindingCodeService(repository, profileService)
    }

    @Test
    fun `generate requires attached profile`() {
        val result = bindingCodeService.generate(hyperZonePlayer)

        assertFalse(result.success)
    }

    @Test
    fun `use binds credentials consumes code and attaches profile`() {
        val profileId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val profile = Profile(
            id = profileId,
            name = "BoundPlayer",
            uuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        )
        val credential = FakeCredential()

        hyperZonePlayer.verified = true
        hyperZonePlayer.credentials += credential
        repository.codes["ABC123"] = profileId
        profileService.bindSubmittedCredentialsResult = profile
        profileService.attachProfileResult = profile

        val result = bindingCodeService.use(hyperZonePlayer, "abc123")

        assertTrue(result.success)
        assertTrue(profileService.bindSubmittedCredentialsCalled)
        assertTrue(profileService.attachProfileCalled)
        assertFalse(repository.codes.containsKey("ABC123"))
    }

    private class FakeBindingCodeStore : BindingCodeStore {
        val codes = LinkedHashMap<String, UUID>()

        override fun createOrReplace(code: String, profileId: UUID, createdAt: Long): Boolean {
            codes.entries.removeIf { it.value == profileId }
            codes[code] = profileId
            return true
        }

        override fun findProfileId(code: String): UUID? = codes[code]

        override fun consume(code: String): Boolean = codes.remove(code) != null
    }

    private class FakeProfileService : HyperZoneProfileService {
        var attachedProfile: Profile? = null
        lateinit var bindSubmittedCredentialsResult: Profile
        var attachProfileResult: Profile? = null
        var bindSubmittedCredentialsCalled: Boolean = false
        var attachProfileCalled: Boolean = false

        override fun getProfile(profileId: UUID): Profile? = attachedProfile?.takeIf { it.id == profileId }

        override fun getAttachedProfile(player: HyperZonePlayer): Profile? = attachedProfile

        override fun attachProfile(player: HyperZonePlayer, profileId: UUID): Profile? {
            attachProfileCalled = true
            attachedProfile = attachProfileResult
            return attachProfileResult
        }

        override fun canResolveOrCreateProfile(userName: String, uuid: UUID?): Boolean = true

        override fun tryResolveOrCreateProfile(userName: String, uuid: UUID?) = error("not used in this test")

        override fun resolveOrCreateProfile(player: HyperZonePlayer, userName: String?, uuid: UUID?): Profile {
            error("not used in this test")
        }

        override fun attachVerifiedCredentialProfile(player: HyperZonePlayer): Profile? = attachedProfile

        override fun bindSubmittedCredentials(player: HyperZonePlayer, profileId: UUID): Profile {
            bindSubmittedCredentialsCalled = true
            return bindSubmittedCredentialsResult
        }
    }

    private class FakeHyperZonePlayer : HyperZonePlayer {
        override val clientOriginalName: String = "Alice"
        override val clientOriginalUUID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        override val isOnlinePlayer: Boolean = true
        val credentials = mutableListOf<HyperZoneCredential>()
        var verified: Boolean = false

        override fun hasAttachedProfile(): Boolean = false

        override fun submitCredential(credential: HyperZoneCredential) {
            credentials += credential
        }

        override fun getSubmittedCredentials(): List<HyperZoneCredential> = credentials

        override fun isVerified(): Boolean = verified

        override fun canBind(): Boolean = verified

        override fun overVerify() {}

        override fun resetVerify() {
            verified = false
            credentials.clear()
        }

        override fun sendMessage(message: Component) {}

        override fun getTemporaryGameProfile() = error("not used")

        override fun getAttachedGameProfile() = error("not used")

        override fun setTemporaryGameProfile(profile: com.velocitypowered.api.util.GameProfile?) {}
    }

    private class FakeCredential : HyperZoneCredential {
        override val channelId: String = "test"
        override val credentialId: String = "cred"

        override fun getBoundProfileId(): UUID? = null

        override fun bind(profileId: UUID): Boolean = true
    }
}


