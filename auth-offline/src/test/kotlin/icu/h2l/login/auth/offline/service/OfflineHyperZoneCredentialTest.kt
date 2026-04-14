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

package icu.h2l.login.auth.offline.service

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.login.auth.offline.api.db.OfflineAuthTable
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import java.util.UUID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OfflineHyperZoneCredentialTest {
    private lateinit var repository: OfflineAuthRepository
    private lateinit var pendingRegistrations: PendingOfflineRegistrationManager
    private lateinit var credential: OfflineHyperZoneCredential
    private lateinit var pendingRegistrationId: UUID
    private lateinit var database: Database
    private val profileId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        val profileTable = ProfileTable()
        val offlineAuthTable = OfflineAuthTable("", profileTable)
        val databaseManager = object : HyperZoneDatabaseManager {
            override val tablePrefix: String = ""

            override fun <T> executeTransaction(statement: () -> T): T {
                return transaction(database) { statement() }
            }
        }
        transaction(database) {
            SchemaUtils.create(profileTable, offlineAuthTable)
            profileTable.insert {
                it[profileTable.id] = profileId
                it[profileTable.name] = "AliceProfile"
                it[profileTable.uuid] = UUID.fromString("33333333-3333-4333-8333-333333333333")
            }
        }
        repository = OfflineAuthRepository(databaseManager, offlineAuthTable)
        pendingRegistrations = PendingOfflineRegistrationManager()
        pendingRegistrationId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        pendingRegistrations.put(
            PendingOfflineRegistrationManager.PendingOfflineRegistration(
                credentialUuid = pendingRegistrationId,
                normalizedName = "alice",
                passwordHash = "hashed-password",
                hashFormat = "sha256"
            )
        )
        credential = OfflineHyperZoneCredential(
            repository = repository,
            pendingRegistrations = pendingRegistrations,
            normalizedName = "alice",
            pendingRegistrationId = pendingRegistrationId
        )
    }

    @Test
    fun `rename updates pending registration name used during bind`() {
        credential.onRegistrationNameChanged("Alice_Renamed")
        assertEquals("alice_renamed", pendingRegistrations.get(pendingRegistrationId)?.normalizedName)

        val bindResult = credential.bind(profileId)

        assertTrue(bindResult)
        assertEquals(profileId, repository.getByName("alice_renamed")?.profileId)
    }

    @Test
    fun `bind consumes renamed pending registration data`() {
        credential.onRegistrationNameChanged("alice_new")

        assertNull(credential.getBoundProfileId())
        assertTrue(credential.bind(profileId))
        assertNull(pendingRegistrations.get(pendingRegistrationId))
        assertEquals(profileId, repository.getByName("alice_new")?.profileId)
    }
}






