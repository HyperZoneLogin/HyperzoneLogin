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

package icu.h2l.login.profile

import icu.h2l.api.profile.CredentialChannelRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * [CredentialChannelRegistry] 的 Velocity 侧实现。
 *
 * 使用 [ConcurrentHashMap] 派生的并发集合存储渠道 ID，保证并发安全。
 * 注册操作是幂等的，重复注册同一渠道 ID 不会产生副作用。
 */
class CredentialChannelRegistryImpl : CredentialChannelRegistry {

    private val channelIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun registerChannel(channelId: String) {
        channelIds.add(channelId)
    }

    override fun isRegistered(channelId: String): Boolean {
        return channelId in channelIds
    }

    override fun getRegisteredChannelIds(): Set<String> {
        return channelIds.toSet()
    }
}
