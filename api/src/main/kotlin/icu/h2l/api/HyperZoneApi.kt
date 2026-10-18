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

package icu.h2l.api

import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.command.HyperChatCommandManagerProvider
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.net.HyperZoneNetProvider
import icu.h2l.api.player.HyperZonePlayerAccessorProvider
import icu.h2l.api.profile.CredentialChannelRegistry
import icu.h2l.api.vServer.HyperZoneVServerProvider
import java.nio.file.Path

/**
 * HyperZoneLogin 主插件向子模块暴露的顶层 API 入口。
 *
 * 通过该接口可访问代理实例、数据目录、数据库能力，以及命令、玩家访问器、等待区适配器等
 * 常用 provider 接口。
 */
interface HyperZoneApi :
    HyperChatCommandManagerProvider,
    HyperZonePlayerAccessorProvider,
    HyperZoneVServerProvider,
    HyperZoneNetProvider {
    /**
     * 当前运行中的 Velocity 代理实例。
     */
    val proxy: ProxyServer

    /**
     * 主插件及子模块共享的数据目录根路径。
     */
    val dataDirectory: Path

    /**
     * 供模块访问数据库事务与表前缀配置的统一入口。
     */
    val databaseManager: HyperZoneDatabaseManager

    /**
     * 凭证渠道注册表。
     *
     * 子模块注册时，核心层会将 [HyperSubModule.credentialChannelIds] 中的渠道 ID 自动写入此注册表。
     * 外部插件也可以通过此入口主动注册自定义凭证渠道。
     */
    val credentialChannelRegistry: CredentialChannelRegistry

    /**
     * 注册一个子模块到当前核心运行时。
     */
    fun registerModule(module: HyperSubModule)
}

/**
 * [HyperZoneApi] 的全局访问器。
 *
 * 适用于无法通过依赖注入直接拿到主插件实例的子模块初始化场景。
 */
object HyperZoneApiProvider {
    @Volatile
    private var api: HyperZoneApi? = null

    /**
     * 绑定当前运行时的 [HyperZoneApi] 实例。
     */
    fun bind(api: HyperZoneApi) {
        this.api = api
    }

    /**
     * 获取已绑定的 [HyperZoneApi]，若主插件尚未初始化完成则抛错。
     */
    fun get(): HyperZoneApi = api ?: error("HyperZoneLogin API is not available yet")

    /**
     * 获取已绑定的 [HyperZoneApi]，若当前尚未可用则返回 `null`。
     */
    fun getOrNull(): HyperZoneApi? = api
}
