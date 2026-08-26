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
import icu.h2l.api.player.getChannel
import icu.h2l.login.manager.LoginManager
import icu.h2l.login.player.VelocityHyperZonePlayer
import icu.h2l.login.vServer.outpre.OutPreState
import icu.h2l.login.vServer.outpre.OutPreVServerAuth
import icu.h2l.login.vServer.outpre.handler.OutPreAuthSessionHandler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * struck 模式的初始进入会话。
 *
 * 该模式默认不接入认证服桥接；若线上认证失败/超时，再切换成 waiting-area bridge 会话。
 */
internal class StruckInitialJoinSession(
    override val player: ConnectedPlayer,
    override val hyperPlayer: VelocityHyperZonePlayer,
    internal val handler: OutPreAuthSessionHandler,
    override val state: OutPreState,
) : OutPreInitialJoinSession {
    private val fallbackStarted = AtomicBoolean(false)

    override fun destroy() = Unit

    override fun begin(owner: OutPreVServerAuth) {
        owner.publishAuthStartEvent(player, hyperPlayer, state, "beginInitialJoin")
            .whenCompleteAsync({ result, throwable ->
                if (throwable != null) {
                    fallbackToWaitingArea(owner, "error=${throwable.message}")
                    return@whenCompleteAsync
                }
                if (!player.isActive || hyperPlayer.hasAttachedProfile()) {
                    return@whenCompleteAsync
                }
                when (result?.status) {
                    LoginManager.Status.SUCCESS,
                    LoginManager.Status.ALREADY_VERIFIED -> Unit

                    LoginManager.Status.NO_CLAIMS,
                    LoginManager.Status.ALL_FAILED,
                    LoginManager.Status.TIMEOUT,
                    LoginManager.Status.INTERNAL_ERROR,
                    null -> {
                        fallbackToWaitingArea(owner, "result=${result?.status} reason=${result?.reason}")
                    }
                }
            }, player.getChannel().eventLoop())
        owner.trace(
            "beginInitialJoin struck-suspended channel=${player.getChannel().id()} player=${player.username} ${
                owner.describeState(state)
            }"
        )
    }

    override fun release(owner: OutPreVServerAuth, handler: OutPreAuthSessionHandler, preferredTargetServerName: String?) {
        owner.closeInitialSession(player)
        handler.onReleased(preferredTargetServerName)
    }

    override fun onVerified(owner: OutPreVServerAuth) {
        owner.continueVerifiedInitialJoin(player, handler, state)
    }

    override fun onAuthServerJoined(owner: OutPreVServerAuth) = Unit

    private fun fallbackToWaitingArea(owner: OutPreVServerAuth, reason: String) {
        if (!fallbackStarted.compareAndSet(false, true)) {
            return
        }
        if (!player.isActive || hyperPlayer.hasAttachedProfile()) {
            return
        }
        val fallbackSession = OutPreInitialJoinSessionFactory.waitingAreaFallbackFrom(this)
        owner.replaceInitialSession(player.getChannel(), fallbackSession)
        owner.trace(
            "outpre.struck fallback-to-waiting-area channel=${player.getChannel().id()} player=${player.username} $reason ${
                owner.describeState(fallbackSession.state)
            }"
        )
        fallbackSession.begin(owner)
    }
}
