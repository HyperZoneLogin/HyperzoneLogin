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

package icu.h2l.login.auth.online.main

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.HyperZoneApiProvider
import java.util.logging.Logger

@Plugin(id = "hzl-auth-yggd", name = "HyperZoneLogin - Auth Yggdrasil")
class AuthYggdPlugin @Inject constructor(
    private val server: ProxyServer
) {
    private val logger = Logger.getLogger("hzl-auth-yggd")
    @Subscribe
    fun onEnable(@Suppress("UNUSED_PARAMETER") e: ProxyInitializeEvent) {
        val mainPluginPresent = server.pluginManager.getPlugin("hyperzonelogin").isPresent
        if (mainPluginPresent) {
            try {
                HyperZoneApiProvider.get().registerModule(YggdrasilSubModule())
            } catch (t: Throwable) {
                logger.warning("Failed to register YggdrasilSubModule: ${t.message}")
            }
        } else {
            logger.warning("HyperZoneLogin main plugin not found; YggdrasilSubModule will wait until available.")
        }
    }
}


