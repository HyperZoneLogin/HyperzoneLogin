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
import icu.h2l.api.event.auth.AuthenticationFailureEvent
import icu.h2l.login.safe.service.IpCooldownManager

class AuthFailureGuardListener(
    private val cooldownManager: IpCooldownManager,
    private val logger: java.util.logging.Logger
) {
    @Subscribe
    fun onAuthenticationFailure(event: AuthenticationFailureEvent) {
        val playerIp = event.playerIp?.takeUnless { it.isBlank() } ?: return
        val cooldown = cooldownManager.recordViolation(playerIp) ?: return
        logger.warning(
            "Safe auth-failure guard 已对 IP $playerIp 启动冷却 ${cooldown.remainingSeconds}s, " +
                "user=${event.userName}, authType=${event.authType}, reason=${event.reason}"
        )
    }
}

