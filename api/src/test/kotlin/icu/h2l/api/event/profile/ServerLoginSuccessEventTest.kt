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

package icu.h2l.api.event.profile

import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.player.HyperZonePlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class ServerLoginSuccessEventTest {
    @Test
    fun `defaults mirror packet fields and take property snapshots`() {
        val sourceProperties = mutableListOf(
            GameProfile.Property("textures", "value", "signature")
        )

        val event = ServerLoginSuccessEvent(
            hyperZonePlayer = fakeHyperZonePlayer(),
            currentUuid = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            currentUsername = "PlayerOne",
            currentProperties = sourceProperties
        )

        assertFalse(event.rewritePacket)
        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), event.uuid)
        assertEquals("PlayerOne", event.username)
        assertEquals(sourceProperties, event.currentProperties)
        assertEquals(sourceProperties, event.properties)
        assertNotSame(sourceProperties, event.currentProperties)
        assertNotSame(sourceProperties, event.properties)
        assertNotSame(event.currentProperties, event.properties)

        sourceProperties += GameProfile.Property("extra", "v", "sig")

        assertEquals(1, event.currentProperties?.size)
        assertEquals(1, event.properties?.size)
    }

    @Test
    fun `nullable properties stay nullable`() {
        val event = ServerLoginSuccessEvent(
            hyperZonePlayer = fakeHyperZonePlayer(),
            currentUuid = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            currentUsername = "PlayerTwo",
            currentProperties = null
        )

        assertNull(event.currentProperties)
        assertNull(event.properties)
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeHyperZonePlayer(): HyperZonePlayer {
        return Proxy.newProxyInstance(
            HyperZonePlayer::class.java.classLoader,
            arrayOf(HyperZonePlayer::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "FakeHyperZonePlayer"
                "hashCode" -> 0
                "equals" -> false
                else -> defaultValue(method.returnType)
            }
        } as HyperZonePlayer
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            Void.TYPE -> null
            else -> null
        }
    }
}




