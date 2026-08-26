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

package icu.h2l.login.vServer.outpre.session

import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import icu.h2l.login.player.VelocityHyperZonePlayer
import icu.h2l.login.vServer.outpre.OutPreState
import icu.h2l.login.vServer.outpre.OutPreVServerAuth
import icu.h2l.login.vServer.outpre.handler.OutPreAuthSessionHandler

/**
 * outpre 初次进入流程的模式会话。
 *
 * struck 与 waiting-area bridge 都实现这份协议，
 * 但两者的 begin / verified / failure 处理逻辑完全不同。
 */
internal sealed interface OutPreInitialJoinSession {
    val player: ConnectedPlayer
    val hyperPlayer: VelocityHyperZonePlayer
    val state: OutPreState

    fun destroy()

    fun begin(owner: OutPreVServerAuth)

    fun onVerified(owner: OutPreVServerAuth)

    fun onAuthServerJoined(owner: OutPreVServerAuth)

    fun release(owner: OutPreVServerAuth, handler: OutPreAuthSessionHandler, preferredTargetServerName: String?)
}
