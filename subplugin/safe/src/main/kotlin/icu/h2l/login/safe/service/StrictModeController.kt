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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StrictModeController(
    private val enabled: Boolean,
    windowSeconds: Int,
    private val triggerAttempts: Int,
    private val recoverAfterSeconds: Int,
    private val logger: java.util.logging.Logger
) {
    data class StrictModeState(
        val active: Boolean,
        val remainingSeconds: Long = 0
    )

    private val windowMillis = windowSeconds.coerceAtLeast(1) * 1000L
    private val globalAttempts = ConcurrentLinkedQueue<Long>()
    private val activeUntil = AtomicLong(0)
    private val announcedActive = AtomicBoolean(false)

    fun recordAttemptAndGetState(): StrictModeState {
        if (!enabled) {
            return StrictModeState(false)
        }

        val now = System.currentTimeMillis()
        cleanup(now)
        globalAttempts.add(now)
        if (globalAttempts.size >= triggerAttempts.coerceAtLeast(1)) {
            val nextActiveUntil = now + recoverAfterSeconds.coerceAtLeast(1) * 1000L
            val previous = activeUntil.getAndUpdate { current -> maxOf(current, nextActiveUntil) }
            if (nextActiveUntil > previous && announcedActive.compareAndSet(false, true)) {
                logger.warning("Safe strict mode 已启用：检测到短时间内大量连接请求")
            }
        }
        return currentState(now)
    }

    private fun currentState(now: Long = System.currentTimeMillis()): StrictModeState {
        val deadline = activeUntil.get()
        if (deadline <= now) {
            if (announcedActive.compareAndSet(true, false)) {
                logger.info("Safe strict mode 已恢复到普通模式")
            }
            return StrictModeState(false)
        }
        return StrictModeState(true, ((deadline - now) / 1000).coerceAtLeast(1))
    }

    private fun cleanup(now: Long) {
        while (true) {
            val head = globalAttempts.peek() ?: break
            if (now - head < windowMillis) {
                break
            }
            globalAttempts.poll()
        }
    }
}

