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

package icu.h2l.login.inject.network.netty

import com.google.common.collect.Multimap
import com.velocitypowered.proxy.network.ConnectionManager
import com.velocitypowered.proxy.network.Endpoint
import icu.h2l.api.log.warn
import icu.h2l.login.reflect.FuzzyLookup
import icu.h2l.login.reflect.VelocityInternalAccess
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 客户端（玩家方向）`ChannelInitializer` 的包装器。
 *
 * 与后端 [HZLChannelInitializer] 不同，这里的 handler 分两类放置：
 * - **before**：挂到 Velocity 的 `"handler"` 之前（inbound 登录链路）；
 * - **after**：挂到 pipeline 末尾（outbound，例如 login success 重写）。
 *
 * 通过 [install] 自举：内部完成「读取 server initializer 原子引用 → 用自身包裹原
 * initializer → 写回」，调用方只需一行 [install] 即可完成注入。已绑定 listener 的既有
 * endpoint 通过 [retrofitExistingEndpoints] 兜底回注。
 */
class HZLServerChannelInitializer(
    private val original: ChannelInitializer<Channel>,
    private val cm: ConnectionManager,
) : ChannelInitializer<Channel>() {

    private data class Entry(
        val name: String,
        val factory: (Channel) -> ChannelHandler?,
    )

    private val beforeHandlers = CopyOnWriteArrayList<Entry>()
    private val afterHandlers = CopyOnWriteArrayList<Entry>()

    /** 注册一个挂在 `"handler"` 之前的 handler 工厂；factory 返回 null 表示跳过当前连接。 */
    fun registerBefore(name: String, factory: (Channel) -> ChannelHandler?) {
        beforeHandlers.removeIf { it.name == name }
        beforeHandlers.add(Entry(name, factory))
    }

    /** 注册一个挂在 pipeline 末尾的 handler 工厂；factory 返回 null 表示跳过当前连接。 */
    fun registerAfter(name: String, factory: (Channel) -> ChannelHandler?) {
        afterHandlers.removeIf { it.name == name }
        afterHandlers.add(Entry(name, factory))
    }

    override fun initChannel(ch: Channel) {
        INIT_CHANNEL_METHOD.invoke(original, ch)
        injectInto(ch)
    }

    /** 向一条已初始化的客户端连接注入 before/after handler。 */
    fun injectInto(channel: Channel) {
        val pipeline = channel.pipeline()

        if (pipeline.get("handler") != null) {
            beforeHandlers.forEach { entry ->
                if (pipeline.names().contains(entry.name)) return@forEach
                val handler = entry.factory(channel) ?: return@forEach
                runCatching {
                    pipeline.addBefore("handler", entry.name, handler)
                }.onFailure { throwable ->
                    warn { "[HZLServerChannelInitializer] 前置 handler '${entry.name}' 挂载失败：${throwable.message}" }
                }
            }
        }

        afterHandlers.forEach { entry ->
            if (pipeline.names().contains(entry.name)) return@forEach
            val handler = entry.factory(channel) ?: return@forEach
            runCatching {
                pipeline.addLast(entry.name, handler)
            }.onFailure { throwable ->
                warn { "[HZLServerChannelInitializer] 后置 handler '${entry.name}' 挂载失败：${throwable.message}" }
            }
        }
    }

    /** 为已绑定 listener 的既有 endpoint 补装 accept 适配器。 */
    fun retrofitExistingEndpoints() {
        endpoints.values().forEach { endpoint ->
            endpoint.channel.eventLoop().execute { injectToEndpoint(endpoint.channel) }
        }
    }

    private fun injectToEndpoint(channel: Channel) {
        if (channel.pipeline().names().contains(SERVER_INJECTED_PIPELINE_NAME)) return
        channel.pipeline().addFirst(SERVER_INJECTED_PIPELINE_NAME, object : SeverChannelAcceptAdapter() {
            override fun init(channel: Channel) = injectInto(channel)
        })
    }

    private val endpoints: Multimap<InetSocketAddress, Endpoint> = VelocityInternalAccess.getEndpoints(cm)

    companion object {
        private const val SERVER_INJECTED_PIPELINE_NAME = "s_init_h2l"

        private val INIT_CHANNEL_METHOD: Method =
            ChannelInitializer::class.java.getDeclaredMethod("initChannel", Channel::class.java)
                .also { it.isAccessible = true }

        /**
         * 自举安装：接管 Velocity 的 server initializer，并用自身包裹原实现。
         *
         * - 若已安装过则直接返回已有实例，避免重复包裹。
         */
        fun install(cm: ConnectionManager): HZLServerChannelInitializer {
            val initializerHolder =
                FuzzyLookup.fieldByNames(ConnectionManager::class.java, "serverChannelInitializer").get(cm)
            val getMethod = initializerHolder.javaClass.getMethod("get")
            val setMethod = initializerHolder.javaClass.getMethod("set", ChannelInitializer::class.java)

            val old = getMethod.invoke(initializerHolder) as ChannelInitializer<Channel>
            if (old is HZLServerChannelInitializer) {
                return old
            }

            val wrapped = HZLServerChannelInitializer(old, cm)
            setMethod.invoke(initializerHolder, wrapped)
            return wrapped
        }
    }
}
