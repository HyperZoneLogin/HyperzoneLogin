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

package icu.h2l.login.backend.nanolimbo

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import icu.h2l.api.HyperZoneApi
import icu.h2l.api.log.info
import icu.h2l.api.log.warn
import icu.h2l.api.message.HyperZoneModuleMessageResources
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.vServer.HyperZoneVServerLimboReporter
import icu.h2l.login.backend.nanolimbo.command.NanoLimboCommand
import ua.nanit.limbo.server.HyperZoneLimboServer
import java.net.InetSocketAddress
import java.nio.file.Files

class NanoLimboSubModule : HyperSubModule {
    @Volatile
    private var limboServer: HyperZoneLimboServer? = null

    override fun register(api: HyperZoneApi) {
        val dataDirectory = api.dataDirectory.resolve("backend-nanolimbo")
        Files.createDirectories(dataDirectory)

        HyperZoneModuleMessageResources.copyBundledLocales(api.dataDirectory, "backend-nanolimbo", javaClass.classLoader)

        val server = HyperZoneLimboServer(dataDirectory)
        limboServer = server

        api.proxy.eventManager.register(api, object {
            @Subscribe
            fun onShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
                runCatching { limboServer?.stop() }
                    .onFailure { throwable -> warn { "backend-nanolimbo 停止失败: ${throwable.message}" } }
            }
        })

        val startupThread = Thread {
            runCatching { server.start() }
                .onSuccess { reportLimboBoundAddress(server) }
                .onFailure { throwable -> warn { "backend-nanolimbo 启动失败: ${throwable.message}" } }
        }
        startupThread.name = "backend-nanolimbo-startup"
        startupThread.isDaemon = true
        startupThread.start()

        registerCommand(api, server)

        info { "backend-nanolimbo 已加载，NanoLimbo 后端正在 ${dataDirectory.toAbsolutePath()} 启动" }
    }

    private fun registerCommand(api: HyperZoneApi, server: HyperZoneLimboServer) {
        val command = NanoLimboCommand(server).createCommand()
        val meta = api.proxy.commandManager.metaBuilder(command)
            .aliases(COMMAND_ALIAS)
            .build()
        api.proxy.commandManager.register(meta, command)
        info { "backend-nanolimbo 管理命令 /$COMMAND_NAME 与 /$COMMAND_ALIAS 已注册" }
    }

    private fun reportLimboBoundAddress(server: HyperZoneLimboServer) {
        val address = server.boundAddress() as? InetSocketAddress ?: return
        HyperZoneVServerLimboReporter.report(address.hostString, address.port)
        info { "backend-nanolimbo 已把实际监听地址 ${address.hostString}:${address.port} 回填给核心层" }
    }

    companion object {
        private const val COMMAND_NAME = "hzl-limbo"
        private const val COMMAND_ALIAS = "hzllimbo"
    }
}
