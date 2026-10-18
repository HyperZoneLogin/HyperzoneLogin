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

/**
 * `CHAT_SESSION_UPDATE` killer 的**兜底退役预算**。
 *
 * `CHAT_SESSION_UPDATE` 由客户端在登录成功、进入 PLAY 之后立刻发出，
 * 因此在它的生命周期内，本 handler 只应当看到**少量**未命中的包。
 *
 * 但如果客户端被改动、或对端插件根本不发这个包，本 handler 就会永远留在流水线上，
 * 对每一个发往后端的包都跑一遍结构解析。本类用「已放行包数」给这个窗口封顶：
 * 一旦超出预算仍未命中，调用方应当打印警告并自我卸载。
 *
 * 预算分两档，因为「登录成功」这份联动信号有无时，安全余量并不一样：
 *
 * - [ARMED_DEFAULT]：已观察到客户端 login success —— chat session 包**应当**紧随其后，
 *   余量可以很小（默认 256 个包，远大于实际所需）；
 * - [UNARMED_DEFAULT]：尚未观察到 login success（典型情况是仍处于后端登录阶段）——
 *   保守放大到 [UNARMED_DEFAULT] 个包，避免在联动信号缺失时过早卸载而漏杀。
 *
 * 注意这是**最后一道保险**：即使联动信号永远不来，计数也一定会把 handler 摘掉。
 */
internal class ChatSessionKillerLifetime(
    private val armedBudget: Int = ARMED_DEFAULT,
    private val unarmedBudget: Int = UNARMED_DEFAULT,
) {

    private var forwarded: Int = 0

    /**
     * 记录一个「已放行（未命中）」的包，并判断是否应当放弃。
     *
     * @param armed 是否已观察到客户端 login success。
     * @return true 表示已超出该档预算，调用方应当警告并卸载。
     */
    fun onForwarded(armed: Boolean): Boolean {
        forwarded++
        return forwarded > if (armed) armedBudget else unarmedBudget
    }

    /** 已放行的包数，用于日志诊断。 */
    fun forwardedCount(): Int = forwarded

    companion object {
        /** 已知 login success 后的包数预算。 */
        const val ARMED_DEFAULT: Int = 256

        /** 未知 login success 时的包数预算。 */
        const val UNARMED_DEFAULT: Int = 4096
    }
}
