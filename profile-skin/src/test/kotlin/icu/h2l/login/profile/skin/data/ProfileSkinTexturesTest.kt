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

package icu.h2l.login.profile.skin.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProfileSkinTexturesTest {
    @Test
    fun `toPropertyOrNull returns null when signature is missing`() {
        val textures = ProfileSkinTextures(
            value = "base64-textures",
            signature = null
        )

        assertNull(textures.toPropertyOrNull())
    }

    @Test
    fun `toPropertyOrNull returns property when signature is present`() {
        val textures = ProfileSkinTextures(
            value = "base64-textures",
            signature = "signed-value"
        )

        val property = textures.toPropertyOrNull()

        assertNotNull(property)
        assertEquals("textures", property!!.name)
        assertEquals("base64-textures", property.value)
        assertEquals("signed-value", property.signature)
    }

    @Test
    fun `toProperty throws clear error when signature is missing`() {
        val textures = ProfileSkinTextures(
            value = "base64-textures",
            signature = ""
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            textures.toProperty()
        }

        assertEquals(
            "ProfileSkinTextures cannot be converted to GameProfile.Property without a non-blank signature",
            error.message
        )
    }
}

