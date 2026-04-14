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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class RenameProfileCreatePolicyTest {
    @Test
    fun `allows rename when only uuid is conflicting elsewhere`() {
        val reason = RenameProfileCreatePolicy.getBlockedReason(
            requestedName = "AliceRenamed",
            requestedUuid = UUID.fromString("22222222-2222-4222-8222-222222222222"),
            existingByName = null
        )

        assertNull(reason)
    }

    @Test
    fun `blocks rename when requested name is already mapped to another uuid`() {
        val existingProfile = Profile(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            name = "AliceRenamed",
            uuid = UUID.fromString("33333333-3333-4333-8333-333333333333")
        )

        val reason = RenameProfileCreatePolicy.getBlockedReason(
            requestedName = "AliceRenamed",
            requestedUuid = UUID.fromString("22222222-2222-4222-8222-222222222222"),
            existingByName = existingProfile
        )

        assertEquals("名称 AliceRenamed 已被其他 UUID 占用", reason)
    }

    @Test
    fun `blocks rename when requested name already resolves to an existing profile`() {
        val existingProfile = Profile(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            name = "AliceRenamed",
            uuid = UUID.fromString("22222222-2222-4222-8222-222222222222")
        )

        val reason = RenameProfileCreatePolicy.getBlockedReason(
            requestedName = "AliceRenamed",
            requestedUuid = existingProfile.uuid,
            existingByName = existingProfile
        )

        assertEquals("名称 AliceRenamed 已映射到现有 Profile: ${existingProfile.id}", reason)
    }
}

