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

package icu.h2l.login.safe

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.log.info
import icu.h2l.api.module.HyperSubModule
import icu.h2l.login.safe.config.SafeConfigLoader
import icu.h2l.login.safe.listener.PreLoginGuardListener
import icu.h2l.login.safe.service.ConnectionRateLimiter
import icu.h2l.login.safe.service.UsernameValidator

class SafeSubModule : HyperSubModule {
    override fun register(api: HyperZoneApi) {
        val config = SafeConfigLoader.load(api.dataDirectory)
        val listener = PreLoginGuardListener(
            config = config,
            globalRateLimiter = ConnectionRateLimiter(config.globalRateLimit.windowSeconds, config.globalRateLimit.maxAttempts),
            ipRateLimiter = ConnectionRateLimiter(config.ipRateLimit.windowSeconds, config.ipRateLimit.maxAttempts),
            usernameValidator = UsernameValidator(config.username)
        )
        api.proxy.eventManager.register(api, listener)
        info { "SafeSubModule 已加载，入口层安全防护已启用" }
    }
}

