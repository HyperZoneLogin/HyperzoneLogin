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
import icu.h2l.api.config.HZLConfig
import icu.h2l.api.config.HZLConfigRegistry
import icu.h2l.api.log.info
import icu.h2l.api.log.warn
import icu.h2l.api.message.HyperZoneModuleMessageResources
import icu.h2l.api.module.HyperSubModule
import icu.h2l.login.backend.hzlimbo.command.HzLimboCommand
import icu.h2l.login.backend.hzlimbo.config.HzLimboConfig
import icu.h2l.login.backend.hzlimbo.config.HzLimboConfigLoader
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * 内置 HzLimbo 后端模块。
 *
 * 与 backend-nanolimbo 不同，本模块直接复用 Velocity 的网络栈（compileOnly velocity-proxy），
 * 只实现 limbo 特有的连接状态机与固定数据下发；不做 infoForwarding。
 *
 * 配置见 `backend-hzlimbo.conf`。监听地址的解析规则：
 * - 上层 `vServer.mode = outpre`：直接复用 `v-server.outpre.auth-host` / `auth-port`；
 * - 其他模式：使用 `backend-hzlimbo.conf` 的 `bind.host` / `bind.port`。
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

        val config = HzLimboConfigLoader.load(api.dataDirectory)
        registerConfigAccess(api)

        val bindAddress = resolveBindAddress(config)
        val server = HzLimboServer(velocityServer, bindAddress, config)
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
                .onSuccess { info { "backend-hzlimbo 已启动，监听 ${server.boundAddress()}" } }
                .onFailure { throwable -> warn { "backend-hzlimbo 启动失败: ${throwable.message}" } }
        }
        startupThread.name = "backend-hzlimbo-startup"
        startupThread.isDaemon = true
        startupThread.start()

        registerCommand(api, server)

        info { "backend-hzlimbo 已加载，后端正在 ${bindAddress} 启动" }
    }

    private fun registerConfigAccess(api: HyperZoneApi) {
        val access = object : HZLConfig<HzLimboConfig>(
            api.dataDirectory,
            HzLimboConfigLoader.FILE_NAME,
            { HzLimboConfig() },
        ) {}
        access.reload()
        HZLConfigRegistry.register("backend-hzlimbo", access)
    }

    private fun registerCommand(api: HyperZoneApi, server: HzLimboServer) {
        val command = HzLimboCommand(server).createCommand()
        val meta = api.proxy.commandManager.metaBuilder(command).aliases(COMMAND_ALIAS).build()
        api.proxy.commandManager.register(meta, command)
        info { "backend-hzlimbo 管理命令 /$COMMAND_NAME 与 /$COMMAND_ALIAS 已注册" }
    }

    /**
     * 解析 limbo 的监听地址。
     *
     * `vServer.mode = outpre` 时直接读取核心配置里的 outpre 认证服地址，保证与
     * 转接目标完全一致；其他模式使用模块自己的 `bind` 配置。
     */
    private fun resolveBindAddress(config: HzLimboConfig): InetSocketAddress {
        val core = HZLConfigRegistry.get("core")
        val mode = (core?.get("config.vserver.mode") as? String)?.trim()?.lowercase()
        if (mode == "outpre" && core != null) {
            val host = (core.get("config.vserver.outpre.auth-host") as? String)?.trim().orEmpty()
            val port = (core.get("config.vserver.outpre.auth-port") as? Int) ?: -1
            if (host.isNotBlank() && port in 1..65535) {
                return InetSocketAddress(host, port)
            }
            warn { "outpre 的 auth-host/auth-port 无效（host='$host', port=$port），回退到 ${HzLimboConfigLoader.FILE_NAME}" }
        }

        val host = config.bind.host.trim()
        val port = config.bind.port
        require(host.isNotBlank()) { "${HzLimboConfigLoader.FILE_NAME} 的 bind.host 不能为空" }
        require(port in 1..65535) { "${HzLimboConfigLoader.FILE_NAME} 的 bind.port 无效: $port" }
        return InetSocketAddress(host, port)
    }

    companion object {
        private const val COMMAND_NAME = "hzlimbo"
        private const val COMMAND_ALIAS = "hz-limbo"
    }
}
