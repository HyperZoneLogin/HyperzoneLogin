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

package icu.h2l.login.database

import icu.h2l.api.db.InGameProfileCandidate
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.InGameProfileConflictException
import icu.h2l.login.database.helper.InGameProfileDatabaseHelper
import icu.h2l.login.database.helper.UserDatabaseHelper
import icu.h2l.login.database.DatabaseManager
import icu.h2l.login.profile.VelocityHyperZoneUserService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.*

class InGameProfileDatabaseTest {
    @Test
    fun `create profile writes active in-game identity and resolves through it`() {
        withDatabase { context ->
            val gameUuid = UUID.randomUUID()
            val profile = context.service.createUser("Alice", gameUuid)

            val activeIdentity = context.service.getActiveInGameProfile(profile.id)
            assertNotNull(activeIdentity)
            assertEquals(gameUuid, activeIdentity!!.uuid)
            assertEquals("Alice", activeIdentity.name)
            assertNotNull(profile.activeInGameProfileId)

            val resolved = context.service.getUser(profile.id)
            assertEquals(profile, resolved)
            assertEquals(profile.id, context.service.getUserByGameUuid(gameUuid)?.id)
            assertEquals(profile.id, context.service.getUserByName("Alice")?.id)
            assertEquals(1, context.service.getInGameProfiles(profile.id).size)
        }
    }

    @Test
    fun `new user default identity always uses hzl generated uuid`() {
        withDatabase { context ->
            val channelUuid = UUID.randomUUID()
            val credential = mockk<HyperZoneCredential>(relaxed = true)
            every { credential.channelId } returns "test"
            every { credential.getRegistrationName() } returns "Alice"
            every { credential.getSuggestedGameUuid() } returns channelUuid

            val user = context.service.createUser(credential)
            val active = context.service.getActiveInGameProfile(user.id)!!

            assertEquals(context.service.defaultGameUuid("Alice"), active.uuid)
            assertNotEquals(channelUuid, active.uuid)

            context.service.createInGameProfile(
                user.id,
                InGameProfileCandidate(
                    channel = "test",
                    name = "Alice",
                    uuid = channelUuid,
                ),
            )

            assertEquals(2, context.service.getInGameProfiles(user.id).size)
            assertEquals(active.id, context.service.getActiveInGameProfile(user.id)!!.id)
        }
    }

    @Test
    fun `existing in-game identity is reused even when channel name differs`() {
        withDatabase { context ->
            val gameUuid = UUID.randomUUID()
            val profile = context.service.createUser("Alice", gameUuid)
            val existing = context.service.getActiveInGameProfile(profile.id)!!

            val reused = context.service.findInGameProfileByUuid(gameUuid)

            assertEquals(existing.id, reused!!.id)
            assertEquals("Alice", reused.name)
            assertEquals(gameUuid, reused.uuid)
            assertEquals("Alice", context.service.getActiveInGameProfile(profile.id)!!.name)
        }
    }

    @Test
    fun `multiple channels reuse the same identity by uuid`() {
        withDatabase { context ->
            val gameUuid = UUID.randomUUID()
            val profile = context.service.createUser("Alice", gameUuid)

            val viaYggdrasil = context.service.findInGameProfileByUuid(gameUuid)
            val viaFloodgate = context.service.findInGameProfileByUuid(gameUuid)

            assertNotNull(viaYggdrasil)
            assertEquals(viaYggdrasil!!.id, viaFloodgate!!.id)
            assertEquals(1, context.service.getInGameProfiles(profile.id).size)
        }
    }

    @Test
    fun `same profile allows same name with different uuid but other profile conflicts`() {
        withDatabase { context ->
            val profileA = context.service.createUser("Alice", UUID.randomUUID())
            val secondUuid = UUID.randomUUID()
            context.service.createInGameProfile(
                profileA.id,
                InGameProfileCandidate(
                    channel = "test",
                    name = "Alice",
                    uuid = secondUuid,
                ),
            )

            assertEquals(2, context.service.getInGameProfiles(profileA.id).size)

            assertThrows(InGameProfileConflictException::class.java) {
                context.service.createInGameProfile(
                    profileA.id,
                    InGameProfileCandidate(
                        channel = "test",
                        name = "AliceAgain",
                        uuid = secondUuid,
                    ),
                )
            }

            val profileB = context.service.createUser("Bob", UUID.randomUUID())

            assertThrows(InGameProfileConflictException::class.java) {
                context.service.createInGameProfile(
                    profileA.id,
                    InGameProfileCandidate(
                        channel = "test",
                        name = "Bob",
                        uuid = UUID.randomUUID(),
                    ),
                )
            }

            assertThrows(InGameProfileConflictException::class.java) {
                context.service.createInGameProfile(
                    profileA.id,
                    InGameProfileCandidate(
                        channel = "test",
                        name = "Alice2",
                        uuid = context.service.getActiveInGameProfile(profileB.id)!!.uuid,
                    ),
                )
            }
        }
    }

    @Test
    fun `switch active in-game profile changes resolved identity`() {
        withDatabase { context ->
            val firstUuid = UUID.randomUUID()
            val profile = context.service.createUser("Alice", firstUuid)
            val secondUuid = UUID.randomUUID()
            val second = context.service.createInGameProfile(
                profile.id,
                InGameProfileCandidate(
                    channel = "test",
                    name = "AliceBedrock",
                    uuid = secondUuid,
                ),
            )

            context.service.switchActiveInGameProfile(profile.id, second.id)

            val activeIdentity = context.service.getActiveInGameProfile(profile.id)
            assertNotNull(activeIdentity)
            assertEquals(secondUuid, activeIdentity!!.uuid)
            assertEquals("AliceBedrock", activeIdentity.name)
        }
    }

    private fun withDatabase(
        block: (TestContext) -> Unit,
    ) {
        val path = Files.createTempFile("hzl-ingame-profile-test", ".db")
        val manager = DatabaseManager(
            config = DatabaseConfig.sqlite(path = path.toString()),
            proxy = mockk(relaxed = true),
        )
        try {
            manager.connect()
            manager.createBaseTables()

            val userDatabaseHelper = UserDatabaseHelper(manager, manager.getUserTable())
            val inGameProfileDatabaseHelper = InGameProfileDatabaseHelper(manager, manager.getInGameProfileTable())
            val service = VelocityHyperZoneUserService(userDatabaseHelper, inGameProfileDatabaseHelper)
            block(
                TestContext(
                    service = service,
                    userDatabaseHelper = userDatabaseHelper,
                    inGameProfileDatabaseHelper = inGameProfileDatabaseHelper,
                    manager = manager,
                )
            )
        } finally {
            manager.disconnect()
            Files.deleteIfExists(path)
            Files.deleteIfExists(path.resolveSibling("${path.fileName}-wal"))
            Files.deleteIfExists(path.resolveSibling("${path.fileName}-shm"))
        }
    }

    private data class TestContext(
        val service: VelocityHyperZoneUserService,
        val userDatabaseHelper: UserDatabaseHelper,
        val inGameProfileDatabaseHelper: InGameProfileDatabaseHelper,
        val manager: DatabaseManager,
    )
}
