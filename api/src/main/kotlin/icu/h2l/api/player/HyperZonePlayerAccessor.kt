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

package icu.h2l.api.player

import com.velocitypowered.api.proxy.Player
import io.netty.channel.Channel
import java.util.*

interface HyperZonePlayerAccessor {
    /**
     * 为一个新的连接创建登录期玩家对象。
     *
     * 该操作应只在预登录初始化阶段执行一次；
     * 如果同一 channel 重复创建，调用方应视为流程错误。
     */
    fun create(channel: Channel, userName: String, uuid: UUID, isOnline: Boolean): HyperZonePlayer

    /**
     * 根据代理层 `Player` 获取当前登录期 `HyperZonePlayer`。
     *
     * 在 `DisconnectEvent`、切服事件、状态清理等代理事件回调中，
     * 应优先使用该方法回到登录态对象，而不是遍历所有 `HyperZonePlayer`
     * 并比较 `getProxyPlayerOrNull()` 做反向定位。
     */
    fun getByPlayer(player: Player): HyperZonePlayer

    /**
     * 根据底层连接 channel 获取当前登录期 `HyperZonePlayer`。
     */
    fun getByChannel(channel: Channel): HyperZonePlayer
}

interface HyperZonePlayerAccessorProvider {
    val hyperZonePlayers: HyperZonePlayerAccessor
}
