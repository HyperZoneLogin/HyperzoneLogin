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

import com.velocitypowered.proxy.network.ConnectionManager
import icu.h2l.api.log.warn
import icu.h2l.api.net.BackendChannelHandlerRegistry
import icu.h2l.login.reflect.VelocityInternalAccess
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 后端 `ChannelInitializer` 的包装器，同时充当 [BackendChannelHandlerRegistry]。
 *
 * 参考 ViaVersion 的 `ViaChannelInitializer`。与原实现不同，这里不再要求子类覆写
 * `injectChannel`，而是把「需要追加的 handler」维护成一张带名字的工厂列表：
 *
 * - 保留 [INIT_CHANNEL_METHOD]，先反射调用原始 initializer 的私有 `initChannel`；
 * - 再按顺序把列表里的工厂实例化并挂到 pipeline 上；
 * - [register] / [unregister] 对列表增删，影响之后新建的后端连接。
 *
 * 通过 [install] 自举：它内部完成「读取后端 initializer 原子引用 → 用自身包裹原 initializer
 * → 写回」的全过程，调用方只需一行 [install] 即可完成注入并拿到注册器。
 */
class HZLChannelInitializer(
    private val original: ChannelInitializer<Channel>,
) : ChannelInitializer<Channel>(), BackendChannelHandlerRegistry {

    private data class Entry(
        val name: String,
        val factory: (Channel) -> ChannelHandler,
    )

    private val handlers = CopyOnWriteArrayList<Entry>()

    override fun register(name: String, factory: (Channel) -> ChannelHandler) {
        unregister(name)
        handlers.add(Entry(name, factory))
    }

    override fun unregister(name: String) {
        handlers.removeIf { it.name == name }
    }

    override fun initChannel(ch: Channel) {
        INIT_CHANNEL_METHOD.invoke(original, ch)

        handlers.forEach { entry ->
            runCatching {
                ch.pipeline().addLast(entry.name, entry.factory(ch))
            }.onFailure { throwable ->
                warn { "[HZLChannelInitializer] 后端 handler '${entry.name}' 挂载失败：${throwable.message}" }
            }
        }
    }

    companion object {
        private val INIT_CHANNEL_METHOD: Method =
            ChannelInitializer::class.java.getDeclaredMethod("initChannel", Channel::class.java)
                .also { it.isAccessible = true }

        /**
         * 自举安装：接管 Velocity 的后端 initializer，并用自身包裹原实现。
         *
         * - backendChannelInitializer 在部分 Velocity 版本中标注了 @Deprecated，
         *   这里通过 [VelocityInternalAccess] 反射访问，避免直接 API 依赖；
         * - 若已安装过则直接返回已有实例，避免重复包裹。
         */
        fun install(cm: ConnectionManager): HZLChannelInitializer {
            val initializerHolder = VelocityInternalAccess.getBackendChannelInitializerHolder(cm)
            val getMethod = initializerHolder.javaClass.getMethod("get")
            val setMethod = initializerHolder.javaClass.getMethod("set", ChannelInitializer::class.java)

            val old = getMethod.invoke(initializerHolder) as ChannelInitializer<Channel>
            if (old is HZLChannelInitializer) {
                return old
            }

            val wrapped = HZLChannelInitializer(old)
            setMethod.invoke(initializerHolder, wrapped)
            return wrapped
        }
    }
}
