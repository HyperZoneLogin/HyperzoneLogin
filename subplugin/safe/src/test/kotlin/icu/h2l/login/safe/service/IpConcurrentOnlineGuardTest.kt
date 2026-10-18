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

package icu.h2l.login.safe.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpConcurrentOnlineGuardTest {

    @Test
    fun `limits concurrent sessions per IP`() {
        val guard = IpConcurrentOnlineGuard(enabled = true, maxConcurrent = 2)

        assertTrue(guard.tryReserve("1.2.3.4", "a"))
        assertTrue(guard.tryReserve("1.2.3.4", "b"))
        assertFalse(guard.tryReserve("1.2.3.4", "c"), "third session from same IP should be rejected")
        assertEquals(2, guard.currentCount("1.2.3.4"))
    }

    @Test
    fun `release frees a slot for the same IP`() {
        val guard = IpConcurrentOnlineGuard(enabled = true, maxConcurrent = 2)

        assertTrue(guard.tryReserve("1.2.3.4", "a"))
        assertTrue(guard.tryReserve("1.2.3.4", "b"))
        assertFalse(guard.tryReserve("1.2.3.4", "c"))

        guard.release("a")
        assertTrue(guard.tryReserve("1.2.3.4", "c"), "slot must be reusable after release")
        assertEquals(2, guard.currentCount("1.2.3.4"))
    }

    @Test
    fun `release is idempotent`() {
        val guard = IpConcurrentOnlineGuard(enabled = true, maxConcurrent = 1)

        assertTrue(guard.tryReserve("1.2.3.4", "a"))
        guard.release("a")
        guard.release("a") // double release must not go negative or free twice

        assertEquals(0, guard.currentCount("1.2.3.4"))
        assertTrue(guard.tryReserve("1.2.3.4", "b"))
    }

    @Test
    fun `different IPs are independent`() {
        val guard = IpConcurrentOnlineGuard(enabled = true, maxConcurrent = 1)

        assertTrue(guard.tryReserve("1.2.3.4", "a"))
        assertTrue(guard.tryReserve("5.6.7.8", "b"), "a different IP has its own quota")
        assertFalse(guard.tryReserve("1.2.3.4", "c"))

        assertEquals(1, guard.currentCount("1.2.3.4"))
        assertEquals(1, guard.currentCount("5.6.7.8"))
    }

    @Test
    fun `disabled guard always admits`() {
        val guard = IpConcurrentOnlineGuard(enabled = false, maxConcurrent = 1)

        assertTrue(guard.tryReserve("1.2.3.4", "a"))
        assertTrue(guard.tryReserve("1.2.3.4", "b"))
        guard.release("a")
        assertEquals(0, guard.currentCount("1.2.3.4"), "disabled guard does not track counts")
    }

    @Test
    fun `max concurrent zero means unlimited`() {
        val guard = IpConcurrentOnlineGuard(enabled = true, maxConcurrent = 0)

        assertTrue(guard.tryReserve("1.2.3.4", "a"))
        assertTrue(guard.tryReserve("1.2.3.4", "b"))
        assertTrue(guard.tryReserve("1.2.3.4", "c"))
    }
}
