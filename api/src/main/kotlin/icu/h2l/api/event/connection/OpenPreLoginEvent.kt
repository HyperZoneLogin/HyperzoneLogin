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

package icu.h2l.api.event.connection

import com.velocitypowered.api.event.annotation.AwaitingEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import java.util.*

/**
 * 触发PreLogin后触发，用于鉴别是否为离线玩家以及进行离线的验证.
 *
 * Velocity typically fires this event asynchronously and does not wait for a response. However,
 * it will wait for all [DisconnectEvent]s for every player on the proxy to fire
 * successfully before the proxy shuts down. This event is the sole exception to the
 * [AwaitingEvent] contract.
 */
@AwaitingEvent
class OpenPreLoginEvent(
    val uuid: UUID,
    val userName: String,
    val host: String,
    val playerIp: String,
    val channel: Channel
) {
    var isOnline: Boolean = true
    var allow: Boolean = true
    var disconnectMessage: Component = Component.text("未知的登录前置拦截原因")
}
