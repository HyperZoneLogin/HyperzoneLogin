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

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.proxy.VelocityServer
import icu.h2l.api.HyperZoneApi
import icu.h2l.api.log.info
import icu.h2l.api.log.warn
import icu.h2l.api.message.HyperZoneModuleMessageResources
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.vServer.HyperZoneVServerLimboReporter
import icu.h2l.login.backend.hzlimbo.command.HzLimboCommand
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * 内置 HzLimbo 后端模块。
 *
 * 与 backend-nanolimbo 不同，本模块直接复用 Velocity 的网络栈（compileOnly velocity-proxy），
 * 只实现 limbo 特有的连接状态机与固定数据下发；不做 infoForwarding，ping / brand / 玩家列表
 * 全部固定，不提供任何外部配置。
 */
class HzLimboSubModule : HyperSubModule {
    @Volatile
    private var limboServer: HzLimboServer? = null

    override fun register(api: HyperZoneApi) {
        val velocityServer = api.proxy as? VelocityServer
            ?: error("backend-hzlimbo 需要运行在 Velocity 代理上")

        val dataDirectory = api.dataDirectory.resolve("backend-hzlimbo")
        Files.createDirectories(dataDirectory)
        HyperZoneModuleMessageResources.copyBundledLocales(api.dataDirectory, "backend-hzlimbo", javaClass.classLoader)

        val bindAddress = resolveBindAddress()
        val server = HzLimboServer(velocityServer, bindAddress)
        limboServer = server

        api.proxy.eventManager.register(api, object {
            @Subscribe
            fun onShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
                runCatching { limboServer?.stop() }
                    .onFailure { throwable -> warn { "backend-hzlimbo 停止失败: ${throwable.message}" } }
            }
        })

        val startupThread = Thread {
            runCatching { server.start() }
                .onSuccess { reportLimboBoundAddress(server) }
                .onFailure { throwable -> warn { "backend-hzlimbo 启动失败: ${throwable.message}" } }
        }
        startupThread.name = "backend-hzlimbo-startup"
        startupThread.isDaemon = true
        startupThread.start()

        registerCommand(api, server)

        info { "backend-hzlimbo 已加载，后端正在 ${bindAddress} 启动" }
    }

    private fun registerCommand(api: HyperZoneApi, server: HzLimboServer) {
        val command = HzLimboCommand(server).createCommand()
        val meta = api.proxy.commandManager.metaBuilder(command).aliases(COMMAND_ALIAS).build()
        api.proxy.commandManager.register(meta, command)
        info { "backend-hzlimbo 管理命令 /$COMMAND_NAME 与 /$COMMAND_ALIAS 已注册" }
    }

    private fun reportLimboBoundAddress(server: HzLimboServer) {
        val address = server.boundAddress() as? InetSocketAddress ?: return
        HyperZoneVServerLimboReporter.report(address.hostString, address.port)
        info { "backend-hzlimbo 已把实际监听地址 ${address.hostString}:${address.port} 回填给核心层" }
    }

    private fun resolveBindAddress(): InetSocketAddress {
        val host = System.getProperty(BIND_HOST_PROPERTY, DEFAULT_BIND_HOST)
        val port = System.getProperty(BIND_PORT_PROPERTY, DEFAULT_BIND_PORT.toString())
            .toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("Invalid ${BIND_PORT_PROPERTY}: ${System.getProperty(BIND_PORT_PROPERTY)}")
        return InetSocketAddress(host, port)
    }

    companion object {
        private const val COMMAND_NAME = "hzlimbo"
        private const val COMMAND_ALIAS = "hz-limbo"

        private const val BIND_HOST_PROPERTY = "hzlimbo.bindHost"
        private const val BIND_PORT_PROPERTY = "hzlimbo.bindPort"
        private const val DEFAULT_BIND_HOST = "127.0.0.1"
        private const val DEFAULT_BIND_PORT = 30066
    }
}
