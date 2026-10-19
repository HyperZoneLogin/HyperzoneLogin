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

package icu.h2l.api.message

import com.velocitypowered.api.command.CommandSource
import icu.h2l.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component

/**
 * HyperZoneLogin 统一消息渲染服务。
 *
 * 子模块应通过该服务渲染本地化文案，而不是直接硬编码玩家可见文本。
 */
interface HyperZoneMessageService {
    /**
     * 重新加载全部消息资源。
     */
    fun reload()

    /**
     * 使用默认上下文渲染指定消息键。
     */
    fun render(key: String, vararg placeholders: HyperZoneMessagePlaceholder): Component

    /**
     * 按命令发送者的本地化环境渲染指定消息键。
     */
    fun render(source: CommandSource?, key: String, vararg placeholders: HyperZoneMessagePlaceholder): Component

    /**
     * 按登录态玩家上下文渲染指定消息键。
     */
    fun render(player: HyperZonePlayer, key: String, vararg placeholders: HyperZoneMessagePlaceholder): Component
}

/**
 * [HyperZoneMessageService] 的全局访问入口。
 */
object HyperZoneMessageServiceProvider {
    @Volatile
    private var service: HyperZoneMessageService? = null

    /**
     * 绑定当前运行时消息服务。
     */
    fun bind(service: HyperZoneMessageService) {
        this.service = service
    }

    /**
     * 获取已绑定的消息服务，若尚未初始化则抛错。
     */
    fun get(): HyperZoneMessageService = service ?: error("HyperZone message service is not available yet")
}

