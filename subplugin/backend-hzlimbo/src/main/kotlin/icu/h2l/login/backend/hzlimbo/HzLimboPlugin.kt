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

package icu.h2l.login.backend.hzlimbo

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.HyperZoneApiProvider
import java.util.logging.Logger

/**
 * `backend-hzlimbo` 作为独立 Velocity 插件加载时的入口。
 *
 * 内置（monolith / 主插件）模式下不会打包 `velocity-plugin.json`，模块由主插件直接实例化；
 * 以 split 分发单独放入 plugins 目录时，则由本类把 [HzLimboSubModule] 注册到主插件。
 */
@Plugin(id = "hzl-backend-hzlimbo", name = "HyperZoneLogin - Backend HzLimbo")
class HzLimboPlugin @Inject constructor(
    private val server: ProxyServer,
) {
    private val logger: Logger = Logger.getLogger("hzl-backend-hzlimbo")

    @Subscribe
    fun onEnable(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        if (server.pluginManager.getPlugin(MAIN_PLUGIN_ID).isEmpty) {
            logger.warning("未找到主插件 $MAIN_PLUGIN_ID，backend-hzlimbo 不会启动")
            return
        }

        val api = HyperZoneApiProvider.getOrNull()
        if (api == null) {
            logger.warning("HyperZoneLogin API 尚未就绪，backend-hzlimbo 不会启动")
            return
        }

        api.registerModule(HzLimboSubModule())
    }

    private companion object {
        const val MAIN_PLUGIN_ID = "hyperzonelogin"
    }
}
