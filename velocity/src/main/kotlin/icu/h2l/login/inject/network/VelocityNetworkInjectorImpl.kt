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
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.inject.network.netty.NettyLoginSessionHandler
import icu.h2l.login.inject.network.netty.SeverChannelAcceptAdapter
import icu.h2l.login.inject.network.netty.ViaChannelInitializer
import icu.h2l.login.chatsession.net.ChatSessionKillerPacketReplacer
import icu.h2l.login.inject.network.netty.replacer.LoginProfilePacketReplacer
import icu.h2l.login.inject.network.netty.replacer.ServerLoginSuccessPacketReplacer
import icu.h2l.login.reflect.VelocityInternalAccess
import icu.h2l.login.vServer.backend.compat.BackendWaitingAreaPlayerInfoFilter
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
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
        private const val LOGIN_SUCCESS_PROFILE_REPLACER = "h2l_login_success_profile"
    }

    private val endpoints: VelocityEndpointMap = VelocityInternalAccess.getEndpoints(cm)

    /** 持有 serverChannelInitializer 原子引用（AtomicReference 包装），通过反射读取。 */
    private val serverChannelInitializerHolder: Any =
        icu.h2l.login.reflect.FuzzyLookup.fieldByNames(
            ConnectionManager::class.java,
            "serverChannelInitializer",
        ).get(cm)

    private val serverChannelInitializerGetMethod =
        serverChannelInitializerHolder.javaClass.getMethod("get")
    private val serverChannelInitializerSetMethod =
        serverChannelInitializerHolder.javaClass.getMethod(
            "set",
            ChannelInitializer::class.java,
        )

    @Volatile
    private var serverInitializerInjected = false

    fun injectToServerInitializer() {
        if (serverInitializerInjected) return

        synchronized(this) {
            if (serverInitializerInjected) return

            val original =
                serverChannelInitializerGetMethod.invoke(serverChannelInitializerHolder) as ChannelInitializer<Channel>
            if (original is HzlServerChannelInitializer) {
                serverInitializerInjected = true
                return
            }

            serverChannelInitializerSetMethod.invoke(
                serverChannelInitializerHolder,
                HzlServerChannelInitializer(this, original),
            )
            serverInitializerInjected = true
        }
    }

    fun injectToServerPipeline() {
        endpoints.values().forEach { endpoint ->
            val channel = endpoint.channel
            channel.eventLoop().execute { injectToEndpoint(channel) }
        }
    }

    private fun injectToEndpoint(channel: Channel) {
        if (channel.pipeline().names().contains(SERVER_INJECTED_PIPELINE_NAME)) return
        channel.pipeline().addFirst(SERVER_INJECTED_PIPELINE_NAME, object : SeverChannelAcceptAdapter() {
            override fun init(channel: Channel) = injectToAcceptedChannel(channel)
        })
    }

    private fun injectToAcceptedChannel(channel: Channel) {
        val pipeline = channel.pipeline()
        if (pipeline.names().contains(LOGIN_HANDLER)) {
            if (!pipeline.names().contains(LOGIN_SUCCESS_PROFILE_REPLACER)) {
                pipeline.addLast(LOGIN_SUCCESS_PROFILE_REPLACER, ServerLoginSuccessPacketReplacer(channel))
            }
            return
        }

        val connection = pipeline.get(MinecraftConnection::class.java) ?: return
        if (pipeline.get("handler") == null) return

        pipeline.addBefore(
            "handler", LOGIN_HANDLER,
            NettyLoginSessionHandler(this, connection, channel),
        )
        if (!pipeline.names().contains(LOGIN_SUCCESS_PROFILE_REPLACER)) {
            pipeline.addLast(LOGIN_SUCCESS_PROFILE_REPLACER, ServerLoginSuccessPacketReplacer(channel))
        }
    }

    fun injectToBackend() {
        // backendChannelInitializer 在部分 Velocity 版本中标注了 @Deprecated，
        // 通过 VelocityInternalAccess 反射访问，避免直接 API 依赖。
        val initializerHolder = VelocityInternalAccess.getBackendChannelInitializerHolder(cm)
        val getMethod = initializerHolder.javaClass.getMethod("get")
        val setMethod = initializerHolder.javaClass.getMethod("set", ChannelInitializer::class.java)

        val old = getMethod.invoke(initializerHolder) as ChannelInitializer<Channel>
        setMethod.invoke(initializerHolder, object : ViaChannelInitializer(old) {
            override fun injectChannel(channel: Channel) {
                channel.pipeline().addLast("h2l_chat_session_killer", ChatSessionKillerPacketReplacer(channel))
                channel.pipeline().addLast("sl_r_rpl", LoginProfilePacketReplacer(channel))
                if (HyperZoneLoginMain.getInstance().serverAdapter?.needsBackendPlayerInfoCompat() == true) {
                    channel.pipeline().addLast("h2l_waiting_upsert_filter", BackendWaitingAreaPlayerInfoFilter())
                }
            }

            override fun initChannel(ch: Channel) {
                super.initChannel(ch)
            }
        })
    }

    private class HzlServerChannelInitializer(
        private val injector: VelocityNetworkInjectorImpl,
        private val original: ChannelInitializer<Channel>,
    ) : ChannelInitializer<Channel>() {
        override fun initChannel(channel: Channel) {
            // 通过 VelocityInternalAccess 调用私有的 initChannel 方法
            VelocityInternalAccess.invokeInitChannel(original, channel)
            injector.injectToAcceptedChannel(channel)
        }
    }
}