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
import icu.h2l.login.safe.service.IpCooldownManager
import icu.h2l.login.safe.service.StrictModeController
import icu.h2l.login.safe.service.UsernameValidator
import net.kyori.adventure.text.Component

class PreLoginGuardListener(
    private val config: SafeConfig,
    private val globalRateLimiter: ConnectionRateLimiter,
    private val ipRateLimiter: ConnectionRateLimiter,
    private val strictGlobalRateLimiter: ConnectionRateLimiter,
    private val strictIpRateLimiter: ConnectionRateLimiter,
    private val ipCooldownManager: IpCooldownManager,
    private val strictModeController: StrictModeController,
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

        ipCooldownManager.getCooldownState(event.playerIp)?.let { cooldown ->
            deny(event, "§c你的 IP 已被临时限制，请在 ${cooldown.remainingSeconds} 秒后再试")
            return
        }

        val strictMode = strictModeController.recordAttemptAndGetState()
        val activeGlobalLimiter = if (strictMode.active) strictGlobalRateLimiter else globalRateLimiter
        val activeIpRateLimiter = if (strictMode.active) strictIpRateLimiter else ipRateLimiter

        if (!activeGlobalLimiter.tryAcquire("global")) {
            ipCooldownManager.recordViolation(event.playerIp)
            val strictHint = if (strictMode.active) "（当前为高峰防护模式）" else ""
            deny(event, "§c当前连接请求过于频繁，请稍后再试$strictHint")
            return
        }

        if (!activeIpRateLimiter.tryAcquire(event.playerIp)) {
            val cooldown = ipCooldownManager.recordViolation(event.playerIp)
            if (cooldown != null) {
                deny(event, "§c你的 IP 请求过于频繁，已被临时限制 ${cooldown.remainingSeconds} 秒")
                return
            }

            val strictHint = if (strictMode.active) "（当前为高峰防护模式）" else ""
            deny(event, "§c你的 IP 请求过于频繁，请稍后再试$strictHint")
        }
    }

    private fun deny(event: OpenPreLoginEvent, message: String) {
        event.allow = false
        event.disconnectMessage = Component.text(message)
    }
}

