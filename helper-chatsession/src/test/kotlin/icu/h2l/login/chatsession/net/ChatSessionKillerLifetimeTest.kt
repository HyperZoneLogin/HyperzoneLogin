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

package icu.h2l.login.chatsession.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ChatSessionKillerLifetime] 的兜底预算测试。
 *
 * 这套逻辑是「handler 必有终点」的唯一保证，因此逐边界覆盖。
 */
class ChatSessionKillerLifetimeTest {

    @Test
    fun `allows exactly the armed budget then gives up on the next packet`() {
        val lifetime = ChatSessionKillerLifetime(armedBudget = 3, unarmedBudget = 10)

        assertFalse(lifetime.onForwarded(armed = true))
        assertFalse(lifetime.onForwarded(armed = true))
        assertFalse(lifetime.onForwarded(armed = true))
        // 第 4 个包超预算
        assertTrue(lifetime.onForwarded(armed = true))
        assertEquals(4, lifetime.forwardedCount())
    }

    @Test
    fun `allows exactly the unarmed budget then gives up on the next packet`() {
        val lifetime = ChatSessionKillerLifetime(armedBudget = 1, unarmedBudget = 3)

        assertFalse(lifetime.onForwarded(armed = false))
        assertFalse(lifetime.onForwarded(armed = false))
        assertFalse(lifetime.onForwarded(armed = false))
        assertTrue(lifetime.onForwarded(armed = false))
    }

    @Test
    fun `unarmed budget is larger so the linkage signal is only ever tightening`() {
        val lifetime = ChatSessionKillerLifetime()

        assertTrue(
            ChatSessionKillerLifetime.UNARMED_DEFAULT > ChatSessionKillerLifetime.ARMED_DEFAULT,
            "未联动档的预算必须严格大于已联动档，否则联动只会提前放弃",
        )
        // 在已联动预算之内：即使按未联动档计数也不该放弃
        repeat(ChatSessionKillerLifetime.ARMED_DEFAULT) { lifetime.onForwarded(armed = false) }
        assertFalse(
            lifetime.onForwarded(armed = false),
            "第 ${ChatSessionKillerLifetime.ARMED_DEFAULT + 1} 个包仍在未联动预算内",
        )
    }

    @Test
    fun `switching to armed mid-stream applies the tighter budget`() {
        val lifetime = ChatSessionKillerLifetime(armedBudget = 2, unarmedBudget = 100)

        // 先按未联动档放行若干包
        repeat(5) { assertFalse(lifetime.onForwarded(armed = false)) }

        // 联动信号到达后，因已累计超过 armed 预算，下一次即放弃
        assertTrue(
            lifetime.onForwarded(armed = true),
            "累计计数已超过 armed 预算，联动后应当立即放弃",
        )
    }

    @Test
    fun `never expires before the guarantees kick in`() {
        // 只发少量包（正常情况）时，任何一档都不该放弃
        val lifetime = ChatSessionKillerLifetime()
        repeat(8) { assertFalse(lifetime.onForwarded(armed = true)) }
    }

    @Test
    fun `count starts at zero and increments per forwarded packet`() {
        val lifetime = ChatSessionKillerLifetime(armedBudget = 100, unarmedBudget = 100)

        assertEquals(0, lifetime.forwardedCount())
        lifetime.onForwarded(armed = true)
        assertEquals(1, lifetime.forwardedCount())
        lifetime.onForwarded(armed = false)
        assertEquals(2, lifetime.forwardedCount())
    }
}
