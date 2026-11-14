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

package icu.h2l.api.profile

/**
 * 凭证渠道注册表。
 *
 * 子模块在注册时需向此注册表声明自己将使用的凭证渠道 ID（即 [HyperZoneCredential.channelId]）。
 * 核心层可通过此注册表了解系统中存在哪些认证渠道。
 */
interface CredentialChannelRegistry {

    /**
     * 注册一个凭证渠道 ID。
     *
     * 同一渠道 ID 可被重复注册（幂等），不会报错。
     *
     * @param channelId 凭证渠道唯一标识，与 [HyperZoneCredential.channelId] 对应
     */
    fun registerChannel(channelId: String)

    /**
     * 判断指定凭证渠道 ID 是否已注册。
     */
    fun isRegistered(channelId: String): Boolean

    /**
     * 返回当前已注册的所有凭证渠道 ID 的快照（不可变集合）。
     */
    fun getRegisteredChannelIds(): Set<String>
}

/**
 * [CredentialChannelRegistry] 的全局访问器。
 */
object CredentialChannelRegistryProvider {
    @Volatile
    private var registry: CredentialChannelRegistry? = null

    /**
     * 绑定当前运行时的 [CredentialChannelRegistry] 实例。
     */
    fun bind(registry: CredentialChannelRegistry) {
        this.registry = registry
    }

    /**
     * 获取已绑定的注册表，若尚未初始化则抛错。
     */
    fun get(): CredentialChannelRegistry =
        registry ?: error("HyperZone CredentialChannelRegistry is not available yet")

    /**
     * 获取已绑定的注册表，若当前不可用则返回 `null`。
     */
    fun getOrNull(): CredentialChannelRegistry? = registry
}
