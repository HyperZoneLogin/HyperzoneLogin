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

package icu.h2l.login.safe.listener

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.event.connection.OpenPreLoginEvent
import icu.h2l.login.safe.config.SafeConfig
import icu.h2l.login.safe.service.ConnectionRateLimiter
import icu.h2l.login.safe.service.UsernameValidator
import net.kyori.adventure.text.Component

class PreLoginGuardListener(
    private val config: SafeConfig,
    private val globalRateLimiter: ConnectionRateLimiter,
    private val ipRateLimiter: ConnectionRateLimiter,
    private val usernameValidator: UsernameValidator
) {
    @Subscribe(priority = Short.MAX_VALUE)
    fun onOpenPreLogin(event: OpenPreLoginEvent) {
        if (!config.enable || !event.allow) {
            return
        }

        usernameValidator.validate(event.userName)?.let { reason ->
            deny(event, "§c连接已被入口防护拒绝：$reason")
            return
        }

        if (!globalRateLimiter.tryAcquire("global")) {
            deny(event, "§c当前连接请求过于频繁，请稍后再试")
            return
        }

        if (!ipRateLimiter.tryAcquire(event.playerIp)) {
            deny(event, "§c你的 IP 请求过于频繁，请稍后再试")
        }
    }

    private fun deny(event: OpenPreLoginEvent, message: String) {
        event.allow = false
        event.disconnectMessage = Component.text(message)
    }
}

