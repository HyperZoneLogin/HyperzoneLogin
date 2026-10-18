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

@file:Suppress("unused")

package icu.h2l.login.chatsession

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.HyperZoneApiProvider

@Plugin(id = "hzl-helper-chatsession", name = "HyperZoneLogin - Helper Chat Session")
class HelperChatSessionPlugin @Inject constructor(
    private val server: ProxyServer
) {
    private val logger = java.util.logging.Logger.getLogger("hzl-helper-chatsession")

    @Subscribe
    fun onEnable(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        val mainPluginPresent = server.pluginManager.getPlugin("hyperzonelogin").isPresent
        if (mainPluginPresent) {
            try {
                HyperZoneApiProvider.get().registerModule(HelperChatSessionSubModule())
            } catch (t: Throwable) {
                logger.warning("Failed to register HelperChatSessionSubModule: ${t.message}")
            }
        } else {
            logger.warning("HyperZoneLogin main plugin not found; HelperChatSessionSubModule will wait until available.")
        }
    }
}
