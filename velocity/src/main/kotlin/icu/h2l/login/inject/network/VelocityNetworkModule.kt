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

package icu.h2l.login.inject.network

import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import icu.h2l.api.HyperZoneApi
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.inject.network.listener.NetworkInjectListener
import icu.h2l.login.inject.network.netty.HZLChannelInitializer
import icu.h2l.login.inject.network.netty.HZLServerChannelInitializer
import icu.h2l.login.inject.network.netty.NettyLoginSessionHandler
import icu.h2l.login.inject.network.netty.replacer.LoginProfilePacketReplacer
import icu.h2l.login.inject.network.netty.replacer.ServerLoginSuccessPacketReplacer
import icu.h2l.login.reflect.VelocityInternalAccess
import icu.h2l.login.vServer.backend.compat.BackendWaitingAreaPlayerInfoFilter

/**
 * 核心网络注入入口。
 *
 * 该能力与核心重度耦合，一旦缺失整个插件无法工作，因此**不是**可选子模块，不走
 * [icu.h2l.api.module.HyperSubModule] 那套加载机制，而是在主类里直接 [install]。
 */
object VelocityNetworkModule {

    fun install(api: HyperZoneApi) {
        val proxy = api.proxy as VelocityServer
        val connectionManager = VelocityInternalAccess.getConnectionManager(proxy)

//        发送到服务器方向（后端）
        val backend = HZLChannelInitializer.install(connectionManager)
        backend.register("sl_r_rpl") { channel -> LoginProfilePacketReplacer(channel) }
        if (HyperZoneLoginMain.getInstance().serverAdapter?.needsBackendPlayerInfoCompat() == true) {
            backend.register("h2l_waiting_upsert_filter") { BackendWaitingAreaPlayerInfoFilter() }
        }
        // 暴露给子模块，供其通过 api.net.backendChannelHandlers 注册后端 handler。
        HyperZoneLoginMain.getInstance().setBackendChannelHandlers(backend)

//        发送到玩家方向（客户端）
        val client = HZLServerChannelInitializer.install(connectionManager)
        client.registerBefore("h2l_login_handler") { channel ->
            val connection = channel.pipeline().get(MinecraftConnection::class.java) ?: return@registerBefore null
            NettyLoginSessionHandler(proxy, connection, channel)
        }
        client.registerAfter("h2l_login_success_profile") { channel ->
            ServerLoginSuccessPacketReplacer(channel)
        }
        proxy.eventManager.register(api, NetworkInjectListener(client))
    }
}
