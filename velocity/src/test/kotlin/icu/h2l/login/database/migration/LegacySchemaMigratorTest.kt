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

package icu.h2l.login.database.migration

import icu.h2l.login.database.DatabaseConfig
import icu.h2l.login.database.DatabaseManager
import icu.h2l.login.database.helper.InGameProfileDatabaseHelper
import icu.h2l.login.database.helper.UserDatabaseHelper
import icu.h2l.login.profile.VelocityHyperZoneUserService
import io.mockk.mockk
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.*

class LegacySchemaMigratorTest {
    @Test
    fun `legacy user profile schema is upgraded to user and in-game profile`() {
        val path = Files.createTempFile("hzl-legacy-migration-test", ".db")
        val manager = DatabaseManager(
            config = DatabaseConfig.sqlite(path = path.toString()),
            proxy = mockk(relaxed = true),
        )

        try {
            manager.connect()
            val userUUID = UUID.randomUUID()
            val gameUUID = UUID.randomUUID()
            val skinId = UUID.randomUUID()
            val credentialCode = "ABC123"

            manager.executeTransaction {
                SchemaUtils.create(
                    LegacyUserTable,
                    LegacyInGameProfileTable,
                    LegacyBindingCodeTable,
                    LegacySkinProfileTable,
                    LegacyOfflineAuthTable,
                    LegacyFloodgateAuthTable,
                )
                LegacyUserTable.insert {
                    it[id] = userUUID
                    it[name] = "LegacyUser"
                    it[uuid] = gameUUID
                }
                LegacyBindingCodeTable.insert {
                    it[profileId] = userUUID
                    it[code] = credentialCode
                    it[createdAt] = 1L
                }
                LegacySkinProfileTable.insert {
                    it[profileId] = userUUID
                    it[this.skinId] = skinId
                    it[updatedAt] = 1L
                }
                LegacyOfflineAuthTable.insert {
                    it[profileId] = userUUID
                }
                LegacyFloodgateAuthTable.insert {
                    it[profileId] = userUUID
                }
            }

            val migrator = LegacySchemaMigrator(manager, manager.tablePrefix)
            migrator.migrateStructure()
            manager.createBaseTables()

            val userDatabaseHelper = UserDatabaseHelper(manager, manager.getUserTable())
            val inGameProfileDatabaseHelper = InGameProfileDatabaseHelper(
                manager,
                manager.getInGameProfileTable(),
            )
            migrator.migrateLegacyUserData(userDatabaseHelper, inGameProfileDatabaseHelper)
            migrator.dropLegacyUserColumns()

            val service = VelocityHyperZoneUserService(userDatabaseHelper, inGameProfileDatabaseHelper)
            val user = service.getUser(userUUID)
            assertNotNull(user)
            val active = service.getActiveInGameProfile(userUUID)
            assertNotNull(active)
            assertEquals("LegacyUser", active!!.name)
            assertEquals(gameUUID, active.uuid)

            val userColumns = columnsOf(manager, "hzl_user")
            assertFalse("name" in userColumns)
            assertFalse("uuid" in userColumns)
            assertTrue("active_in_game_profile_id" in userColumns)

            val inGameColumns = columnsOf(manager, "in_game_profile")
            assertTrue("user_uuid" in inGameColumns)
            assertFalse("profile_id" in inGameColumns)
            assertFalse("channel" in inGameColumns)
            assertFalse("created_at" in inGameColumns)

            val bindingColumns = columnsOf(manager, "binding_code")
            assertTrue("user_uuid" in bindingColumns)
            assertFalse("profile_id" in bindingColumns)

            val skinColumns = columnsOf(manager, "skin_profile")
            assertTrue("user_uuid" in skinColumns)
            assertFalse("profile_id" in skinColumns)
            val skinRow = manager.executeTransaction { MigratedSkinProfileTable.selectAll().single() }
            assertEquals(userUUID, skinRow[MigratedSkinProfileTable.userUUID])

            val offlineColumns = columnsOf(manager, "offline_auth")
            assertTrue("user_uuid" in offlineColumns)
            assertFalse("profile_id" in offlineColumns)

            val floodgateColumns = columnsOf(manager, "floodgate_auth")
            assertTrue("user_uuid" in floodgateColumns)
            assertFalse("profile_id" in floodgateColumns)
        } finally {
            manager.disconnect()
            Files.deleteIfExists(path)
            Files.deleteIfExists(path.resolveSibling("${path.fileName}-wal"))
            Files.deleteIfExists(path.resolveSibling("${path.fileName}-shm"))
        }
    }

    private fun columnsOf(manager: DatabaseManager, tableName: String): Set<String> {
        return manager.executeTransaction {
            TransactionManager.current().connection.metadata {
                columns(object : org.jetbrains.exposed.sql.Table(tableName) {}).values
                    .flatten()
                    .map { it.name.lowercase(Locale.ROOT) }
                    .toSet()
            }
        }
    }

    private object LegacyUserTable : org.jetbrains.exposed.sql.Table("profile") {
        val id = uuid("id")
        val name = varchar("name", 32).uniqueIndex()
        val uuid = uuid("uuid").uniqueIndex()
        override val primaryKey = PrimaryKey(id)
    }

    private object LegacyInGameProfileTable : org.jetbrains.exposed.sql.Table("in_game_profile") {
        val id = uuid("id")
        val profileId = uuid("profile_id")
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

    private object LegacyBindingCodeTable : org.jetbrains.exposed.sql.Table("binding_code") {
        val profileId = uuid("profile_id").uniqueIndex()
        val code = varchar("code", 32)
        val createdAt = long("created_at")
    }

    private object LegacySkinProfileTable : org.jetbrains.exposed.sql.Table("skin_profile") {
        val profileId = uuid("profile_id")
        val skinId = uuid("skin_id")
        val updatedAt = long("updated_at")
        override val primaryKey = PrimaryKey(profileId)
    }

    private object MigratedSkinProfileTable : org.jetbrains.exposed.sql.Table("skin_profile") {
        val userUUID = uuid("user_uuid")
        val skinId = uuid("skin_id")
    }

    private object LegacyOfflineAuthTable : org.jetbrains.exposed.sql.Table("offline_auth") {
        val profileId = uuid("profile_id")
    }

    private object LegacyFloodgateAuthTable : org.jetbrains.exposed.sql.Table("floodgate_auth") {
        val profileId = uuid("profile_id")
    }
}
