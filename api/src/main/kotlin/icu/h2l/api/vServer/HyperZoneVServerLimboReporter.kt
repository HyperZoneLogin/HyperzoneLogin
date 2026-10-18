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

package icu.h2l.api.vServer

/**
 * 内置 limbo 后端（backend-nanolimbo）与核心层之间回传「limbo 实际监听地址」的桥。
 *
 * limbo 模块无法直接依赖主插件（velocity）里的配置类，因此通过本入口把启动后的
 * 实际监听地址回填给核心层。核心层据此把 vServer 配置中的 limbo 运行时字段补全，
 * 供网络注入层识别「limbo 登录服」并跳过无关的拦截逻辑。
 *
 * 仅内存回传，不落盘。核心层在加载完成 vServer 配置后调用 [bind] 绑定写入逻辑。
 */
object HyperZoneVServerLimboReporter {

    @Volatile
    private var listener: ((host: String, port: Int) -> Unit)? = null

    /**
     * 绑定核心层的 limbo 地址写入逻辑；重复绑定会覆盖上一次。
     *
     * @param listener 收到 limbo 启动成功回调时执行的写入函数；传 `null` 解除绑定。
     */
    fun bind(listener: ((host: String, port: Int) -> Unit)?) {
        this.listener = listener
    }

    /**
     * 由 limbo 模块在启动成功后调用，回传实际监听地址。
     *
     * @param host 监听 Host
     * @param port 监听 Port
     */
    fun report(host: String, port: Int) {
        listener?.invoke(host, port)
    }
}
