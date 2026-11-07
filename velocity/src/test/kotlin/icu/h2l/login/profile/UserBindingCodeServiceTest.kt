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

import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.db.InGameProfile
import icu.h2l.api.db.User
import icu.h2l.api.player.ClientLoginIdentity
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.login.database.helper.BindingCodeStore
import icu.h2l.login.manager.LoginManager
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class UserBindingCodeServiceTest {
    private lateinit var repository: FakeBindingCodeStore
    private lateinit var userService: FakeProfileService
    private lateinit var bindingCodeService: UserBindingCodeService
    private lateinit var hyperZonePlayer: FakeHyperZonePlayer

    @BeforeEach
    fun setUp() {
        repository = FakeBindingCodeStore()
        userService = FakeProfileService()
        hyperZonePlayer = FakeHyperZonePlayer()
        bindingCodeService = UserBindingCodeService(
            repository,
            userService,
            LoginManager(mockk<ProxyServer>(relaxed = true), userService),
        )
    }

    @Test
    fun `generate requires attached profile`() {
        val result = bindingCodeService.generate(hyperZonePlayer)

        assertFalse(result.success)
    }

    @Test
    fun `generate creates code for attached profile`() {
        val userUUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        userService.attachedUser = User(id = userUUID)
        userService.activeIdentity = InGameProfile(
            id = UUID.randomUUID(),
            userUUID = userUUID,
            name = "BoundPlayer",
            uuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        )

        val result = bindingCodeService.generate(hyperZonePlayer)

        assertTrue(result.success)
        assertEquals(1, repository.replaceCalls)
        assertEquals(userUUID, repository.codes.values.single())
        assertEquals(10, repository.codes.keys.single().length)
    }

    @Test
    fun `generate reuses existing code for attached profile`() {
        val userUUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        userService.attachedUser = User(id = userUUID)
        userService.activeIdentity = InGameProfile(
            id = UUID.randomUUID(),
            userUUID = userUUID,
            name = "BoundPlayer",
            uuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        )
        repository.codes["EXISTING1"] = userUUID

        val result = bindingCodeService.generate(hyperZonePlayer)

        assertTrue(result.success)
        assertEquals(0, repository.replaceCalls)
        assertEquals(setOf("EXISTING1"), repository.codes.keys)
        assertTrue(PlainTextComponentSerializer.plainText().serialize(result.message).contains("EXISTING1"))
    }

    @Test
    fun `use binds credentials consumes code and attaches profile`() {
        val userUUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val profile = User(id = userUUID)
        userService.activeIdentity = InGameProfile(
            id = UUID.randomUUID(),
            userUUID = userUUID,
            name = "BoundPlayer",
            uuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        )
        val credential = FakeCredential()

        hyperZonePlayer.verified = true
        hyperZonePlayer.credentials += credential
        repository.codes["ABC123"] = userUUID
        userService.bindSubmittedCredentialsResult = profile
        userService.attachUserResult = profile

        val result = bindingCodeService.use(hyperZonePlayer, "abc123")

        assertTrue(result.success)
        assertTrue(userService.bindSubmittedCredentialsCalled)
        assertTrue(userService.attachUserCalled)
        assertFalse(repository.codes.containsKey("ABC123"))
    }

    private class FakeBindingCodeStore : BindingCodeStore {
        val codes = LinkedHashMap<String, UUID>()
        var replaceCalls: Int = 0

        override fun replace(code: String, userUUID: UUID, createdAt: Long): Boolean {
            replaceCalls++
            codes.entries.removeIf { it.value == userUUID }
            codes[code] = userUUID
            return true
        }

        override fun findCode(userUUID: UUID): String? = codes.entries.firstOrNull { it.value == userUUID }?.key

        override fun findProfileId(code: String): UUID? = codes[code]

        override fun consume(code: String): Boolean = codes.remove(code) != null

        override fun delete(userUUID: UUID): Boolean {
            return codes.entries.removeIf { it.value == userUUID }
        }

        override fun reassignUser(sourceUserUUID: UUID, targetUserUUID: UUID): Boolean {
            val code = findCode(sourceUserUUID) ?: return false
            codes[code] = targetUserUUID
            return true
        }
    }

    private class FakeProfileService : HyperZoneUserService {
        var attachedUser: User? = null
        var activeIdentity: InGameProfile? = null
        lateinit var bindSubmittedCredentialsResult: User
        var attachUserResult: User? = null
        var bindSubmittedCredentialsCalled: Boolean = false
        var attachUserCalled: Boolean = false

        override fun getUser(userUUID: UUID): User? = attachedUser?.takeIf { it.id == userUUID }

        override fun getUserByName(name: String): User? =
            activeIdentity?.takeIf { it.name.equals(name, ignoreCase = true) }
                ?.let { getUser(it.userUUID) }

        override fun getUserByGameUuid(playerUuid: UUID): User? =
            activeIdentity?.takeIf { it.uuid == playerUuid }
                ?.let { getUser(it.userUUID) }

        override fun getActiveInGameProfile(userUUID: UUID): InGameProfile? =
            activeIdentity?.takeIf { it.userUUID == userUUID }

        override fun getInGameProfiles(userUUID: UUID): List<InGameProfile> =
            activeIdentity?.takeIf { it.userUUID == userUUID }?.let(::listOf).orEmpty()

        override fun getAttachedUser(player: HyperZonePlayer): User? = attachedUser

        override fun attachUser(player: HyperZonePlayer, userUUID: UUID): User? {
            attachUserCalled = true
            attachedUser = attachUserResult
            return attachUserResult
        }

        override fun canCreateUser(credential: HyperZoneCredential): Boolean = true

        override fun createUser(credential: HyperZoneCredential): User {
            error("not used in this test")
        }

        override fun resolveAuthenticatedUser(player: HyperZonePlayer): User? = attachedUser

        override fun bindSubmittedCredentials(player: HyperZonePlayer, userUUID: UUID): User {
            bindSubmittedCredentialsCalled = true
            return bindSubmittedCredentialsResult
        }
    }

    private class FakeHyperZonePlayer : HyperZonePlayer {
        override val loginIdentity = ClientLoginIdentity("Test", UUID.randomUUID())
        override val userUUID: UUID? = null
        override val isOnlinePlayer: Boolean = true
        val credentials = mutableListOf<HyperZoneCredential>()
        var verified: Boolean = false

        override fun hasAttachedUser(): Boolean = false

        override fun getSubmittedCredentials(): List<HyperZoneCredential> = credentials

        override fun canBind(): Boolean = verified


        override fun resetVerify() {
            verified = false
            credentials.clear()
        }

        override fun sendMessage(message: Component) {}

        override fun getTemporaryGameProfile() = error("not used")

        override fun getAttachedGameProfile() = error("not used")
    }

    private class FakeCredential : HyperZoneCredential {
        override val channelId: String = "test"
        override val credentialId: String = "cred"

        override fun getBoundUserUUID(): UUID? = null

        override fun bind(userUUID: UUID): Boolean = true
    }
}

