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

package icu.h2l.login.vServer.backend.compat

import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.db.InGameProfile
import icu.h2l.login.util.buildAttachedIdentityGameProfile
import icu.h2l.login.util.buildDeliveredGameProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class BackendRuntimeProfileCompensatorTest {
    @Test
    fun `delivered game profile applies attached name only by default`() {
        val currentGameProfile = GameProfile(OLD_UUID, "WaitingTemp", emptyList())
        val activeIdentity = InGameProfile(UUID.randomUUID(), PROFILE_ID, "FormalName", NEW_UUID)

        val resolved = buildDeliveredGameProfile(
            currentGameProfile = currentGameProfile,
            activeIdentity = activeIdentity,
            enableNameHotChange = true,
            enableUuidHotChange = false,
        )

        assertEquals("FormalName", resolved.name)
        assertEquals(OLD_UUID, resolved.id)
        assertEquals(currentGameProfile.properties, resolved.properties)
    }

    @Test
    fun `delivered game profile applies both attached name and uuid when enabled`() {
        val currentGameProfile = GameProfile(OLD_UUID, "WaitingTemp", emptyList())
        val activeIdentity = InGameProfile(UUID.randomUUID(), PROFILE_ID, "FormalName", NEW_UUID)

        val resolved = buildDeliveredGameProfile(
            currentGameProfile = currentGameProfile,
            activeIdentity = activeIdentity,
            enableNameHotChange = true,
            enableUuidHotChange = true,
        )

        assertEquals("FormalName", resolved.name)
        assertEquals(NEW_UUID, resolved.id)
    }

    @Test
    fun `attached identity game profile always uses attached uuid and name while keeping properties`() {
        val currentGameProfile = GameProfile(
            OLD_UUID,
            "TemporaryName",
            listOf(GameProfile.Property("textures", "value", "signature"))
        )
        val activeIdentity = InGameProfile(UUID.randomUUID(), PROFILE_ID, "FormalName", NEW_UUID)

        val resolved = buildAttachedIdentityGameProfile(
            currentGameProfile = currentGameProfile,
            activeIdentity = activeIdentity,
        )

        assertEquals("FormalName", resolved.name)
        assertEquals(NEW_UUID, resolved.id)
        assertEquals(currentGameProfile.properties, resolved.properties)
    }

    @Test
    fun `backend runtime profile index compensator rewrites all affected indices`() {
        val connection = Any()
        val nameIndex = linkedMapOf(
            "oldname" to connection,
            "other" to Any(),
        )
        val uuidIndex = linkedMapOf(
            OLD_UUID to connection,
            OTHER_UUID to Any(),
        )
        val backendPlayers = mutableListOf(
            linkedMapOf(OLD_UUID to connection),
            linkedMapOf(OTHER_UUID to Any()),
        )
        var replaced = false
        var rolledBack = false

        BackendRuntimeProfileIndexCompensator.applyCompensatingSync(
            connection = connection,
            newNameLower = "newname",
            oldUuid = OLD_UUID,
            newUuid = NEW_UUID,
            connectionsByName = nameIndex,
            connectionsByUuid = uuidIndex,
            serverPlayers = backendPlayers,
            replaceProfile = { replaced = true },
            rollbackProfile = { rolledBack = true },
        )

        assertTrue(replaced)
        assertFalse(rolledBack)
        assertSame(connection, nameIndex["newname"])
        assertFalse(nameIndex.containsKey("oldname"))
        assertSame(connection, uuidIndex[NEW_UUID])
        assertFalse(uuidIndex.containsKey(OLD_UUID))
        assertSame(connection, backendPlayers[0][NEW_UUID])
        assertFalse(backendPlayers[0].containsKey(OLD_UUID))
        assertEquals(setOf(OTHER_UUID), backendPlayers[1].keys)
    }

    @Test
    fun `backend runtime profile index compensator restores previous state on failure`() {
        val connection = Any()
        val nameIndex = linkedMapOf("oldname" to connection)
        val uuidIndex = linkedMapOf(OLD_UUID to connection)
        val backendPlayers = mutableListOf(linkedMapOf(OLD_UUID to connection))
        val originalNameIndex = LinkedHashMap(nameIndex)
        val originalUuidIndex = LinkedHashMap(uuidIndex)
        val originalBackendIndex = LinkedHashMap(backendPlayers[0])
        var replaced = false
        var rolledBack = false

        assertThrows(IllegalStateException::class.java) {
            BackendRuntimeProfileIndexCompensator.applyCompensatingSync(
                connection = connection,
                newNameLower = "newname",
                oldUuid = OLD_UUID,
                newUuid = NEW_UUID,
                connectionsByName = nameIndex,
                connectionsByUuid = uuidIndex,
                serverPlayers = backendPlayers,
                replaceProfile = { replaced = true },
                rollbackProfile = { rolledBack = true },
                postSyncHook = { throw IllegalStateException("boom") },
            )
        }

        assertTrue(replaced)
        assertTrue(rolledBack)
        assertEquals(originalNameIndex, nameIndex)
        assertEquals(originalUuidIndex, uuidIndex)
        assertEquals(originalBackendIndex, backendPlayers[0])
    }

    @Test
    fun `backend runtime profile index compensator rejects conflicting live mappings`() {
        val connection = Any()
        val conflictingConnection = Any()
        val nameIndex = linkedMapOf(
            "oldname" to connection,
            "newname" to conflictingConnection,
        )
        val uuidIndex = linkedMapOf(
            OLD_UUID to connection,
            OTHER_UUID to conflictingConnection,
        )
        val backendPlayers = mutableListOf(linkedMapOf(OLD_UUID to connection))
        var replaced = false

        assertThrows(IllegalStateException::class.java) {
            BackendRuntimeProfileIndexCompensator.applyCompensatingSync(
                connection = connection,
                newNameLower = "newname",
                oldUuid = OLD_UUID,
                newUuid = NEW_UUID,
                connectionsByName = nameIndex,
                connectionsByUuid = uuidIndex,
                serverPlayers = backendPlayers,
                replaceProfile = { replaced = true },
                rollbackProfile = {},
            )
        }

        assertFalse(replaced)
        assertSame(connection, nameIndex["oldname"])
        assertSame(conflictingConnection, nameIndex["newname"])
        assertSame(connection, uuidIndex[OLD_UUID])
    }

    companion object {
        private val PROFILE_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        private val OLD_UUID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private val NEW_UUID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        private val OTHER_UUID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
    }
}

