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
import com.velocitypowered.api.event.connection.DisconnectEvent
import icu.h2l.api.event.connection.OpenPreLoginEvent
import icu.h2l.api.player.getChannel
import icu.h2l.login.safe.SafeMessages
import icu.h2l.login.safe.config.SafeConfig
import icu.h2l.login.safe.service.ConnectionRateLimiter
import icu.h2l.login.safe.service.IpCooldownManager
import icu.h2l.login.safe.service.IpConcurrentOnlineGuard
import icu.h2l.login.safe.service.StrictModeController
import icu.h2l.login.safe.service.UsernameValidator

class PreLoginGuardListener(
    private val config: SafeConfig,
    private val globalRateLimiter: ConnectionRateLimiter,
    private val ipRateLimiter: ConnectionRateLimiter,
    private val strictGlobalRateLimiter: ConnectionRateLimiter,
    private val strictIpRateLimiter: ConnectionRateLimiter,
    private val ipCooldownManager: IpCooldownManager,
    private val authFailureCooldownManager: IpCooldownManager,
    private val strictModeController: StrictModeController,
    private val usernameValidator: UsernameValidator,
    private val ipConcurrentGuard: IpConcurrentOnlineGuard
) {
    @Subscribe(priority = Short.MAX_VALUE)
    fun onOpenPreLogin(event: OpenPreLoginEvent) {
        if (!event.allow) {
            return
        }

        usernameValidator.validate(event.userName)?.let { reason ->
            deny(event, SafeMessages.entryRejected(reason))
            return
        }

        authFailureCooldownManager.getCooldownState(event.playerIp)?.let { cooldown ->
            deny(event, SafeMessages.authFailureCooldown(cooldown.remainingSeconds))
            return
        }

        ipCooldownManager.getCooldownState(event.playerIp)?.let { cooldown ->
            deny(event, SafeMessages.ipCooldown(cooldown.remainingSeconds))
            return
        }

        val strictMode = strictModeController.recordAttemptAndGetState()
        val activeGlobalLimiter = if (strictMode.active) strictGlobalRateLimiter else globalRateLimiter
        val activeIpRateLimiter = if (strictMode.active) strictIpRateLimiter else ipRateLimiter

        if (!activeGlobalLimiter.tryAcquire("global")) {
            ipCooldownManager.recordViolation(event.playerIp)
            deny(event, SafeMessages.globalRateLimited(strictMode.active))
            return
        }

        if (!activeIpRateLimiter.tryAcquire(event.playerIp)) {
            val cooldown = ipCooldownManager.recordViolation(event.playerIp)
            if (cooldown != null) {
                deny(event, SafeMessages.ipRateLimited(cooldown.remainingSeconds))
                return
            }

            deny(event, if (strictMode.active) SafeMessages.ipRateLimitedStrict() else SafeMessages.ipRateLimited(null))
            return
        }

        // 同 IP 同时在线限额：在所有前置检查通过后才占用一个并发槽位。
        // 槽位随连接断开（DisconnectEvent）释放，因此这里若拒绝则不会有残留计数。
        if (!ipConcurrentGuard.tryReserve(event.playerIp, event.channel.id().asShortText())) {
            deny(event, SafeMessages.ipConcurrentLimitReached(config.ipLimit.max))
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        // 释放该连接在 ipConcurrentGuard 中占用的并发槽位。
        runCatching { ipConcurrentGuard.release(event.player.getChannel().id().asShortText()) }
    }

    private fun deny(event: OpenPreLoginEvent, message: net.kyori.adventure.text.Component) {
        event.allow = false
        event.disconnectMessage = message
    }
}

