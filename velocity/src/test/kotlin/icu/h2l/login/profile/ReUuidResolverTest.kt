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

import icu.h2l.api.util.RemapUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.UUID

class ReUuidResolverTest {
    @Test
    fun `preferred uuid uses configured prefix`() {
        val resolved = ReUuidResolver.resolve(
            userName = "Alice",
            remapPrefix = "HyperZone",
            hasNameConflict = false,
            isUuidTaken = { false }
        )

        assertEquals(RemapUtils.genUUID("Alice", "HyperZone"), resolved)
    }

    @Test
    fun `falls back to first available random uuid when preferred one is occupied`() {
        val preferred = RemapUtils.genUUID("Alice", "HyperZone")
        val occupiedRandom = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val availableRandom = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val randomCandidates = ArrayDeque(listOf(occupiedRandom, availableRandom))

        val resolved = ReUuidResolver.resolve(
            userName = "Alice",
            remapPrefix = "HyperZone",
            hasNameConflict = false,
            isUuidTaken = { candidate -> candidate == preferred || candidate == occupiedRandom },
            randomUuidSupplier = { randomCandidates.removeFirst() }
        )

        assertSame(availableRandom, resolved)
    }

    @Test
    fun `returns null when name conflict already exists`() {
        val resolved = ReUuidResolver.resolve(
            userName = "Alice",
            remapPrefix = "HyperZone",
            hasNameConflict = true,
            isUuidTaken = { false }
        )

        assertNull(resolved)
    }
}

