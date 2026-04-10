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

class IpCooldownManager(
    private val enabled: Boolean,
    windowSeconds: Int,
    private val triggerAttempts: Int,
    private val cooldownSeconds: Int
) {
    data class CooldownState(
        val blocked: Boolean,
        val remainingSeconds: Long
    )

    private val windowMillis = windowSeconds.coerceAtLeast(1) * 1000L
    private val attemptsByIp = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val blockedUntilByIp = ConcurrentHashMap<String, Long>()

    fun getCooldownState(ip: String): CooldownState? {
        if (!enabled) {
            return null
        }
        cleanupBlockedIp(ip)
        val blockedUntil = blockedUntilByIp[ip] ?: return null
        val remainingMillis = blockedUntil - System.currentTimeMillis()
        if (remainingMillis <= 0L) {
            blockedUntilByIp.remove(ip)
            return null
        }
        return CooldownState(true, (remainingMillis / 1000).coerceAtLeast(1))
    }

    fun recordViolation(ip: String): CooldownState? {
        if (!enabled) {
            return null
        }
        val now = System.currentTimeMillis()
        val queue = attemptsByIp.computeIfAbsent(ip) { ConcurrentLinkedQueue() }
        cleanupQueue(queue, now)
        queue.add(now)
        if (queue.size < triggerAttempts.coerceAtLeast(1)) {
            return null
        }

        val blockedUntil = now + cooldownSeconds.coerceAtLeast(1) * 1000L
        blockedUntilByIp[ip] = blockedUntil
        queue.clear()
        return CooldownState(true, cooldownSeconds.coerceAtLeast(1).toLong())
    }

    private fun cleanupBlockedIp(ip: String) {
        val blockedUntil = blockedUntilByIp[ip] ?: return
        if (blockedUntil <= System.currentTimeMillis()) {
            blockedUntilByIp.remove(ip)
        }
    }

    private fun cleanupQueue(queue: ConcurrentLinkedQueue<Long>, now: Long) {
        while (true) {
            val head = queue.peek() ?: break
            if (now - head < windowMillis) {
                break
            }
            queue.poll()
        }
    }
}

