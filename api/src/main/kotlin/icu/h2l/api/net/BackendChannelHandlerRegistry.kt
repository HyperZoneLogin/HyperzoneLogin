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

package icu.h2l.api.net

import io.netty.channel.Channel
import io.netty.channel.ChannelHandler

/**
 * 后端连接 initializer 的 handler 注册器。
 *
 * 核心接管后端 [io.netty.channel.ChannelInitializer] 之后，把「向每条新建后端连接追加
 * handler」这件事抽象成一张带名字的工厂列表：模块注册一个工厂，之后每条新后端连接
 * 初始化时都会被依次调用并挂到 pipeline 上。
 *
 * 注册与卸载只影响**之后**新建的连接，已建立的连接不会被回改。
 */
interface BackendChannelHandlerRegistry {
    /**
     * 注册一个后端 handler 工厂。同名注册会替换旧工厂。
     */
    fun register(name: String, factory: (Channel) -> ChannelHandler)

    /**
     * 卸载指定名称的后端 handler 工厂。名称不存在时不做任何事。
     */
    fun unregister(name: String)

    companion object {
        /** 未提供后端注入能力时的占位实现；一旦使用即抛错，避免静默失效。 */
        val UNSUPPORTED: BackendChannelHandlerRegistry = object : BackendChannelHandlerRegistry {
            override fun register(name: String, factory: (Channel) -> ChannelHandler) {
                throw UnsupportedOperationException(
                    "当前运行时未提供后端 channel handler 注入能力（BackendChannelHandlerRegistry 为 UNSUPPORTED），无法注册 '$name'。"
                )
            }

            override fun unregister(name: String) {
                throw UnsupportedOperationException(
                    "当前运行时未提供后端 channel handler 注入能力（BackendChannelHandlerRegistry 为 UNSUPPORTED），无法卸载 '$name'。"
                )
            }
        }
    }
}
