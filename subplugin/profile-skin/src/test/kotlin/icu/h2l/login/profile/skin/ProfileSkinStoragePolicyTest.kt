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

import icu.h2l.login.profile.skin.data.ProfileSkinSource
import icu.h2l.login.profile.skin.data.ProfileSkinTextures
import icu.h2l.login.profile.skin.db.ProfileSkinCacheRecord
import icu.h2l.login.profile.skin.db.isEligibleForSourceCache
import icu.h2l.login.profile.skin.db.shouldSkipSave
import icu.h2l.login.profile.skin.db.shouldUseSharedCacheEntry
import icu.h2l.login.profile.skin.service.sanitizeFallbackSourceHash
import icu.h2l.login.profile.skin.service.sanitizeFallbackTextures
import icu.h2l.login.profile.skin.service.shouldUseSourceCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class ProfileSkinStoragePolicyTest {
    @Test
    fun `forced restore fallback preserves original signed textures but still allows source cache lookup`() {
        val upstream = ProfileSkinTextures(
            value = "base64-textures",
            signature = "bad-signature"
        )

        val fallback = sanitizeFallbackTextures(upstream, shouldForceRestoreSignedTextures = true)

        assertEquals(upstream, fallback)
        assertNull(sanitizeFallbackSourceHash("source-hash", shouldForceRestoreSignedTextures = true))
        assertTrue(shouldUseSourceCache(shouldForceRestoreSignedTextures = true))
        assertFalse(shouldUseSharedCacheEntry(sourceHash = null, sourceCacheEligible = false))
    }

    @Test
    fun `same source but cleared source hash must still be saved`() {
        val existing = ProfileSkinCacheRecord(
            skinId = UUID.randomUUID(),
            sourceHash = "source-hash",
            sourceCacheEligible = true,
            skinUrl = "https://textures.example/skin.png",
            skinModel = "classic",
            textures = ProfileSkinTextures(
                value = "base64-textures",
                signature = "bad-signature"
            ),
            updatedAt = 1L
        )
        val source = ProfileSkinSource(
            skinUrl = "https://textures.example/skin.png",
            model = "classic"
        )
        val fallback = ProfileSkinTextures(
            value = "base64-textures",
            signature = "bad-signature"
        )

        assertFalse(
            shouldSkipSave(
                existing = existing,
                source = source,
                textures = fallback,
                sourceHash = null,
                sourceCacheEligible = false
            )
        )
    }

    @Test
    fun `unchanged source and textures can skip save`() {
        val existing = ProfileSkinCacheRecord(
            skinId = UUID.randomUUID(),
            sourceHash = "source-hash",
            sourceCacheEligible = true,
            skinUrl = "https://textures.example/skin.png",
            skinModel = "classic",
            textures = ProfileSkinTextures(
                value = "base64-textures",
                signature = null
            ),
            updatedAt = 1L
        )
        val source = ProfileSkinSource(
            skinUrl = "https://textures.example/skin.png",
            model = "classic"
        )

        assertTrue(
            shouldSkipSave(
                existing,
                source,
                ProfileSkinTextures(value = "base64-textures", signature = null),
                sourceHash = "source-hash",
                sourceCacheEligible = true
            )
        )
    }

    @Test
    fun `legacy source cache rows remain eligible for lookup`() {
        val legacy = ProfileSkinCacheRecord(
            skinId = UUID.randomUUID(),
            sourceHash = "source-hash",
            sourceCacheEligible = null,
            skinUrl = "https://textures.example/skin.png",
            skinModel = "classic",
            textures = ProfileSkinTextures(
                value = "base64-textures",
                signature = "signed-value"
            ),
            updatedAt = 1L
        )

        assertTrue(isEligibleForSourceCache(legacy))
    }

    @Test
    fun `only reusable rows with source hash should share cache entries`() {
        assertTrue(shouldUseSharedCacheEntry(sourceHash = "source-hash", sourceCacheEligible = true))
        assertFalse(shouldUseSharedCacheEntry(sourceHash = "source-hash", sourceCacheEligible = false))
        assertFalse(shouldUseSharedCacheEntry(sourceHash = null, sourceCacheEligible = true))
    }
}

