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
 * outpre 初始登录流程会话。
 *
 * 不同模式用不同实现承载完整流程差异：
 * - [BlockingInitialJoinSession]：阻塞认证模式，认证完成后才发送登录成功；
 * - [BridgeWaitingAreaJoinSession]：等待区桥接模式，先发送登录成功并进入认证服，再继续认证。
 *
 * 会话只调用 handler 暴露的通用登录阶段原语，不把模式分支写回 handler。
 */
internal sealed interface OutPreInitialJoinSession {
    val player: ConnectedPlayer
    val hyperPlayer: VelocityHyperZonePlayer
    val handler: OutPreAuthSessionHandler
    val state: OutPreState

    fun destroy()

    /**
     * 客户端刚进入 outpre 初始阶段，由会话决定“先发登录成功”还是“先启动认证”。
     */
    fun onLoginActivated(owner: OutPreVServerAuth)

    /**
     * 客户端登录阶段已完成，且 PostLogin 已触发。
     */
    fun onClientLoginCompleted(owner: OutPreVServerAuth)

    fun onVerified(owner: OutPreVServerAuth)

    fun onAuthServerJoined(owner: OutPreVServerAuth)

    fun release(owner: OutPreVServerAuth, preferredTargetServerName: String?)
}
