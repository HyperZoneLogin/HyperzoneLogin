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

import com.google.common.collect.Multimap
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.network.ConnectionManager
import com.velocitypowered.proxy.network.Endpoint
import icu.h2l.login.inject.network.netty.NettyLoginSessionHandler
import icu.h2l.login.inject.network.netty.SeverChannelAcceptAdapter
import icu.h2l.login.inject.network.netty.ServerLoginSuccessPacketReplacer
import icu.h2l.login.inject.network.netty.ToBackendPacketReplacer
import icu.h2l.login.inject.network.netty.ViaChannelInitializer
import io.netty.channel.Channel
import java.net.InetSocketAddress

private typealias VelocityEndpointMap = Multimap<InetSocketAddress, Endpoint>

@Suppress("UNCHECKED_CAST")
class VelocityNetworkInjectorImpl(
    val cm: ConnectionManager,
    val proxy: VelocityServer,
) {
    companion object {
        private const val SERVER_INJECTED_PIPELINE_NAME = "s_init_h2l"
        private const val LOGIN_HANDLER = "h2l_login_handler"
    }

    private val endpoints: VelocityEndpointMap = ConnectionManager::class.java.getDeclaredField("endpoints").also {
        it.isAccessible = true
    }.get(cm) as VelocityEndpointMap

    fun injectToServerPipeline() {
        endpoints.values().forEach { endpoint ->
            val channel = endpoint.channel
            channel.eventLoop().execute {
                injectToEndpoint(channel)
            }
        }
    }

    private fun injectToEndpoint(channel: Channel) {
        // channel: ServerChannel
        if (channel.pipeline().names().contains(SERVER_INJECTED_PIPELINE_NAME)) return

        channel.pipeline().addFirst(SERVER_INJECTED_PIPELINE_NAME, object : SeverChannelAcceptAdapter() {
            override fun init(channel: Channel) {
//                println("INIT $channel")
//                println(channel.pipeline().names())

                val connection = channel.pipeline().get(MinecraftConnection::class.java)

                channel.pipeline().addBefore(
                    "handler",
                    LOGIN_HANDLER,
                    NettyLoginSessionHandler(
                        this@VelocityNetworkInjectorImpl,
                        connection,
                        channel,
                    )
                )
                channel.pipeline().addLast("h2l_login_success_profile", ServerLoginSuccessPacketReplacer(channel))
            }
        })
    }


    @Suppress("DEPRECATION")
    fun injectToBackend() {
        cm.backendChannelInitializer.let { initializer ->
            val old = initializer.get()

            initializer.set(object : ViaChannelInitializer(old) {
                override fun injectChannel(channel: Channel) {
                    channel.pipeline().addLast("sl_r_rpl", ToBackendPacketReplacer(channel))
//                    println("SVA: ${channel.pipeline().names()}")
                }
            })
        }
    }
}