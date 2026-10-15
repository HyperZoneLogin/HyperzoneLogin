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

package icu.h2l.login.profile.skin

import icu.h2l.login.profile.skin.data.ProfileSkinTextures
import icu.h2l.login.profile.skin.service.parseRestoredMineSkinTextures
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MineSkinResponseParsingTest {
    @Test
    fun `legacy generate response with signature can be parsed`() {
        val body = """
            {"success":true,"data":{"texture":{"value":"value-v1","signature":"sig-v1"}}}
        """.trimIndent()

        assertEquals(
            ProfileSkinTextures(value = "value-v1", signature = "sig-v1"),
            parseRestoredMineSkinTextures(body)
        )
    }

    @Test
    fun `skinsrestorer style v2 response with signature can be parsed`() {
        val body = """
            {"success":true,"skin":{"texture":{"data":{"value":"value-v2","signature":"sig-v2"}}}}
        """.trimIndent()

        assertEquals(
            ProfileSkinTextures(value = "value-v2", signature = "sig-v2"),
            parseRestoredMineSkinTextures(body)
        )
    }

    @Test
    fun `restored response without signature is rejected`() {
        val body = """
            {"success":true,"data":{"texture":{"value":"value-only"}}}
        """.trimIndent()

        val exception = assertThrows(IllegalStateException::class.java) {
            parseRestoredMineSkinTextures(body)
        }

        assertTrue(exception.message.orEmpty().contains("missing signature"))
    }

    @Test
    fun `unsuccessful response is rejected before cache write`() {
        val body = """
            {"success":false,"errors":[{"code":"invalid_image","message":"bad skin"}]}
        """.trimIndent()

        val exception = assertThrows(IllegalStateException::class.java) {
            parseRestoredMineSkinTextures(body)
        }

        assertTrue(exception.message.orEmpty().contains("indicates failure"))
    }
}

