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

/**
 * 核心暴露给子模块的网络注入能力。
 *
 * 只暴露接口，具体实现由核心（Velocity 运行时）提供。
 */
interface HyperZoneNet {
    /** 后端连接 handler 注册器。 */
    val backendChannelHandlers: BackendChannelHandlerRegistry

    companion object {
        /** 无网络注入能力时的空实现。 */
        val UNSUPPORTED: HyperZoneNet = object : HyperZoneNet {
            override val backendChannelHandlers: BackendChannelHandlerRegistry =
                BackendChannelHandlerRegistry.UNSUPPORTED
        }
    }
}

/**
 * 为 [HyperZoneApi] 暴露 [HyperZoneNet] 的 provider 接口。
 */
interface HyperZoneNetProvider {
    val net: HyperZoneNet
        get() = HyperZoneNet.UNSUPPORTED
}
