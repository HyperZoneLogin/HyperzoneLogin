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
import icu.h2l.api.db.table.InGameProfileTable
import icu.h2l.api.db.table.LegacyUserTable
import icu.h2l.api.profile.InGameProfileConflictException
import icu.h2l.login.database.helper.InGameProfileDatabaseHelper
import icu.h2l.login.database.helper.UserDatabaseHelper
import icu.h2l.login.database.migration.UserSchemaMigrator
import icu.h2l.login.database.DatabaseManager
import icu.h2l.login.profile.VelocityHyperZoneUserService
import io.mockk.mockk
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
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

    @Test
    fun `legacy profile row is migrated to an active in-game profile`() {
        withDatabase(createBaseTables = false) { context ->
            val userUUID = insertLegacyProfile(context.manager, "LegacyUser")

            context.manager.createBaseTables()
            context.migrate()

            val migrated = context.service.getUser(userUUID)
            assertNotNull(migrated)
            assertEquals("LegacyUser", context.service.getActiveInGameProfile(userUUID)!!.name)
        }
    }

    @Test
    fun `legacy migration drops obsolete profile columns and keeps names resolvable`() {
        withDatabase(createBaseTables = false) { context ->
            val userUUID = insertLegacyProfile(context.manager, "LegacyByName")
            val legacyUuid = context.legacyUuid

            context.manager.createBaseTables()
            context.migrate()

            val remainingColumns = context.manager.executeTransaction {
                TransactionManager.current().connection.metadata {
                    columns(LegacyUserTable(context.manager.tablePrefix)).values
                        .flatten()
                        .map { it.name.lowercase() }
                        .toSet()
                }
            }
            assertFalse("name" in remainingColumns)
            assertFalse("uuid" in remainingColumns)

            val resolved = context.service.getUserByName("LegacyByName")
            assertNotNull(resolved)
            assertEquals(userUUID, resolved!!.id)
            assertEquals(legacyUuid, context.service.getActiveInGameProfile(userUUID)!!.uuid)
            assertEquals(userUUID, context.service.getUserByGameUuid(legacyUuid)?.id)
            val identity = context.service.getInGameProfiles(userUUID).single()
            assertEquals("LegacyByName", identity.name)
            assertEquals(legacyUuid, identity.uuid)
        }
    }

    @Test
    fun `obsolete in-game profile channel columns are dropped`() {
        withDatabase(createBaseTables = false) { context ->
            context.manager.executeTransaction {
                SchemaUtils.create(LegacyInGameProfileSchema)
            }

            context.manager.createBaseTables()
            context.migrate()

            val remaining = context.manager.executeTransaction {
                TransactionManager.current().connection.metadata {
                    columns(InGameProfileTable(context.manager.tablePrefix)).values
                        .flatten()
                        .map { it.name.lowercase(Locale.ROOT) }
                        .toSet()
                }
            }
            assertFalse("channel" in remaining)
            assertFalse("channel_entry" in remaining)
            assertFalse("channel_account_id" in remaining)
            assertFalse("created_at" in remaining)
            assertFalse("updated_at" in remaining)
            assertTrue("name" in remaining)
            assertTrue("uuid" in remaining)
        }
    }

    private fun insertLegacyProfile(manager: DatabaseManager, name: String): UUID {
        val legacy = LegacyProfileSchema
        val userUUID = UUID.randomUUID()
        manager.executeTransaction {
            SchemaUtils.create(legacy)
            legacy.insert {
                it[legacy.id] = userUUID
                it[legacy.name] = name
                it[legacy.uuid] = LegacyProfileFixture.uuid
            }
        }
        return userUUID
    }

    private fun withDatabase(
        createBaseTables: Boolean = true,
        block: (TestContext) -> Unit,
    ) {
        val path = Files.createTempFile("hzl-ingame-profile-test", ".db")
        val manager = DatabaseManager(
            config = DatabaseConfig.sqlite(path = path.toString()),
            proxy = mockk(relaxed = true),
        )
        try {
            manager.connect()
            if (createBaseTables) {
                manager.createBaseTables()
            }

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
    ) {
        val legacyUuid: UUID
            get() = LegacyProfileFixture.uuid

        fun migrate() {
            UserSchemaMigrator(
                manager = manager,
                userDatabaseHelper = userDatabaseHelper,
                inGameProfileDatabaseHelper = inGameProfileDatabaseHelper,
                tablePrefix = manager.tablePrefix,
            ).migrate()
        }
    }

    private object LegacyProfileFixture {
        val uuid: UUID = UUID.randomUUID()
    }

    private object LegacyInGameProfileSchema : org.jetbrains.exposed.sql.Table("in_game_profile") {
        val id = uuid("id")
        val userUUID = uuid("user_uuid")
        val name = varchar("name", 32)
        val nameLower = varchar("name_lower", 32)
        val uuid = uuid("uuid")
        val channel = varchar("channel", 64)
        val channelEntry = varchar("channel_entry", 64).default("")
        val channelAccountId = varchar("channel_account_id", 255).default("")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val primaryKey = PrimaryKey(id)
    }

    private object LegacyProfileSchema : org.jetbrains.exposed.sql.Table("profile") {
        val id = uuid("id")
        val name = varchar("name", 32).uniqueIndex()
        val uuid = uuid("uuid").uniqueIndex()
        override val primaryKey = PrimaryKey(id)
    }
}
