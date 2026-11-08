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
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.LoginManager
import icu.h2l.login.player.VelocityHyperZonePlayer
import icu.h2l.login.vServer.outpre.OutPreState
import icu.h2l.login.vServer.outpre.OutPreVServerAuth
import icu.h2l.login.vServer.outpre.handler.OutPreAuthSessionHandler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阻塞认证模式的初始登录流程。
 *
 * 客户端停留在 LOGIN 阶段，先在代理侧完成 HyperZone 认证；
 * 认证成功后才发送登录成功包并直接放行到目标服。
 * 认证失败/超时则切换成 waiting-area bridge 会话，补发登录成功后进入认证服。
 */
internal class BlockingInitialJoinSession(
    override val player: ConnectedPlayer,
    override val hyperPlayer: VelocityHyperZonePlayer,
    override val handler: OutPreAuthSessionHandler,
    override val state: OutPreState,
) : OutPreInitialJoinSession {
    private val fallbackStarted = AtomicBoolean(false)

    override fun destroy() = Unit

    override fun onLoginActivated(owner: OutPreVServerAuth) {
        owner.publishAuthStartEvent(player, hyperPlayer, state, "beginInitialJoin")
            .whenCompleteAsync({ result, throwable ->
                if (throwable != null) {
                    fallbackToWaitingArea(owner, "error=${throwable.message}")
                    return@whenCompleteAsync
                }
                if (!player.isActive) {
                    return@whenCompleteAsync
                }
                if (hyperPlayer.hasAttachedUser()) {
                    // dispatch 直接返回 ALREADY_VERIFIED 时，attach 可能早于 proxy player 绑定，
                    // 当时 tryLeaveWaiting 无法完成迁移；这里补触发一次。
                    if (result?.status == LoginManager.Status.ALREADY_VERIFIED) {
                        HyperZoneLoginMain.getInstance().loginManager.onUserAttached(hyperPlayer)
                    }
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
            "beginInitialJoin blocking-suspended channel=${player.getChannel().id()} player=${player.username} ${
                owner.describeState(state)
            }"
        )
    }

    override fun onClientLoginCompleted(owner: OutPreVServerAuth) {
        if (!hyperPlayer.hasAttachedUser()) {
            owner.trace(
                "blocking.onClientLoginCompleted skipped-without-attached-user channel=${player.getChannel().id()} player=${player.username} ${
                    owner.describeState(state)
                }"
            )
            return
        }
        handler.finishRegisteredLogin()
        release(owner, state.returnTargetServerName)
    }

    override fun onVerified(owner: OutPreVServerAuth) {
        handler.finalizeVerifiedLogin(state.returnTargetServerName)
            .thenRunAsync({
                handler.sendLoginSuccess()
            }, player.getChannel().eventLoop())
            .exceptionally { throwable ->
                owner.trace(
                    "blocking.onVerified finalize-failed channel=${player.getChannel().id()} player=${player.username} reason=${throwable.message}"
                )
                null
            }
    }

    override fun onAuthServerJoined(owner: OutPreVServerAuth) = Unit

    override fun release(owner: OutPreVServerAuth, preferredTargetServerName: String?) {
        owner.closeInitialSession(player)
        handler.onReleased(preferredTargetServerName)
    }

    private fun fallbackToWaitingArea(owner: OutPreVServerAuth, reason: String) {
        if (!fallbackStarted.compareAndSet(false, true)) {
            return
        }
        if (!player.isActive || hyperPlayer.hasAttachedUser()) {
            return
        }
        val fallbackSession = OutPreInitialJoinSessionFactory.waitingAreaFallbackFrom(this)
        owner.replaceInitialSession(player.getChannel(), fallbackSession)
        owner.trace(
            "outpre.blocking fallback-to-waiting-area channel=${player.getChannel().id()} player=${player.username} $reason ${
                owner.describeState(fallbackSession.state)
            }"
        )
        // 先替换会话，再补发登录成功；
        // 客户端完成登录阶段后，handler 会分发给当前的 BridgeWaitingAreaJoinSession。
        if (handler.isLoginPhaseCompleted()) {
            fallbackSession.onClientLoginCompleted(owner)
        } else {
            handler.sendLoginSuccess()
        }
    }
}
