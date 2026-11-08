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

package icu.h2l.login.player

import icu.h2l.api.player.ClientLoginIdentity
import icu.h2l.api.util.RemapUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class VelocityHyperZonePlayerTest {
    @Test
    fun `temporary profile is created eagerly and remains stable for the session`() {
        val player = VelocityHyperZonePlayer(
            loginIdentity = ClientLoginIdentity(
                "ClientName",
                RemapUtils.genUUID("ClientName", RemapUtils.REMAP_PREFIX),
            ),
            isOnlinePlayer = false,
        )

        assertNull(player.userUUID)
        val first = player.getTemporaryGameProfile()
        val second = player.getTemporaryGameProfile()

        assertSame(first, second)
        assertTrue(first.name.startsWith(RemapUtils.EXPECTED_NAME_PREFIX))
        assertEquals(RemapUtils.genUUID(first.name, RemapUtils.REMAP_PREFIX), first.id)
    }

    @Test
    fun `login success identity defaults to login identity until explicitly marked`() {
        val loginIdentity = ClientLoginIdentity(
            "ClientName",
            RemapUtils.genUUID("ClientName", RemapUtils.REMAP_PREFIX),
        )
        val player = VelocityHyperZonePlayer(
            loginIdentity = loginIdentity,
            isOnlinePlayer = true,
        )

        assertEquals(loginIdentity, player.loginSuccessIdentity)

        val inGameIdentity = ClientLoginIdentity(
            "InGameName",
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
        )
        player.markLoginSuccessIdentity(inGameIdentity)

        assertEquals(inGameIdentity, player.loginSuccessIdentity)
    }
}

