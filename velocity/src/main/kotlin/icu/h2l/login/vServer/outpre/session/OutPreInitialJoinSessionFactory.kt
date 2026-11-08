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
import icu.h2l.login.vServer.outpre.handler.OutPreAuthSessionHandler

/**
 * 根据阻塞认证 / 等待区桥接模式创建对应的初始登录流程会话。
 */
internal object OutPreInitialJoinSessionFactory {
    fun create(
        blocking: Boolean,
        player: ConnectedPlayer,
        hyperPlayer: VelocityHyperZonePlayer,
        handler: OutPreAuthSessionHandler,
        authTargetLabel: String,
    ): OutPreInitialJoinSession {
        return if (blocking) {
            BlockingInitialJoinSession(
                player = player,
                hyperPlayer = hyperPlayer,
                handler = handler,
                state = OutPreState(
                    authTargetLabel = authTargetLabel,
                    initialFlowPending = true,
                    inAuthHold = false,
                    hasConnectedToAuthServerOnce = true,
                )
            )
        } else {
            BridgeWaitingAreaJoinSession(
                player = player,
                hyperPlayer = hyperPlayer,
                handler = handler,
                state = OutPreState(
                    authTargetLabel = authTargetLabel,
                    initialFlowPending = true,
                    inAuthHold = true,
                    hasConnectedToAuthServerOnce = false,
                ),
                shouldPublishAuthStartAfterJoin = true,
            )
        }
    }

    fun waitingAreaFallbackFrom(
        session: BlockingInitialJoinSession
    ): BridgeWaitingAreaJoinSession {
        session.state.inAuthHold = true
        session.state.hasConnectedToAuthServerOnce = false
        session.state.verifiedExitPending = false
        return BridgeWaitingAreaJoinSession(
            player = session.player,
            hyperPlayer = session.hyperPlayer,
            handler = session.handler,
            state = session.state,
            shouldPublishAuthStartAfterJoin = false,
        )
    }
}
