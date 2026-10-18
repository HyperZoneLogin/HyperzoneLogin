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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class ConnectionRateLimiter(
    windowSeconds: Int,
    private val maxAttempts: Int
) {
    private val windowMillis = windowSeconds.coerceAtLeast(1) * 1000L
    private val attemptsByKey = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    fun tryAcquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        val queue = attemptsByKey.computeIfAbsent(key) { ConcurrentLinkedQueue() }
        cleanup(queue, now)
        if (queue.size >= maxAttempts) {
            return false
        }
        queue.add(now)
        return true
    }

    private fun cleanup(queue: ConcurrentLinkedQueue<Long>, now: Long) {
        while (true) {
            val head = queue.peek() ?: break
            if (now - head < windowMillis) {
                break
            }
            queue.poll()
        }
    }
}

