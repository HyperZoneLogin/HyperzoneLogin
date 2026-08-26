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

import com.velocitypowered.proxy.connection.backend.VelocityServerConnection
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.protocol.StateRegistry
import icu.h2l.api.log.HyperZoneLogApi
import icu.h2l.api.log.error
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.player.getChannel
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.inject.network.NettyReflectionHelper
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.player.VelocityHyperZonePlayer
import icu.h2l.login.reflect.VelocityInternalAccess
import icu.h2l.login.vServer.outpre.OutPreBackendBridge
import icu.h2l.login.vServer.outpre.OutPreState
import icu.h2l.login.vServer.outpre.OutPreVServerAuth
import icu.h2l.login.vServer.outpre.handler.OutPreAuthSessionHandler
import icu.h2l.login.vServer.outpre.handler.bridge.OutPreClientBridgeSessionHandler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.apache.logging.log4j.LogManager

private val logger = LogManager.getLogger(BridgeWaitingAreaJoinSession::class.java)

/**
 * 需要先进入认证服桥接、再开始等待区认证的模式会话。
 */
internal class BridgeWaitingAreaJoinSession(
    override val player: ConnectedPlayer,
    override val hyperPlayer: VelocityHyperZonePlayer,
    internal val handler: OutPreAuthSessionHandler,
    override val state: OutPreState,
    private val shouldPublishAuthStartAfterJoin: Boolean,
) : OutPreInitialJoinSession, OutPreBackendBridge.Callback {

    @Volatile
    private var boundOwner: OutPreVServerAuth? = null

    @Volatile
    private var _bridge: OutPreBackendBridge? = null

    @Volatile
    private var velocityServerConnection: VelocityServerConnection? = null

    override fun destroy() {
        _bridge?.disconnect()
        _bridge = null
    }

    override fun begin(owner: OutPreVServerAuth) {
        boundOwner = owner
        val bridge = owner.createBridge(player).also { _bridge = it }
        velocityServerConnection = bridge.serverConnection
        player.connection.eventLoop().execute {
            NettyReflectionHelper.setConnectionInFlight(player, bridge.serverConnection)
            handler.mcConnection.setActiveSessionHandler(
                if (handler.supportConfig) StateRegistry.CONFIG else StateRegistry.LOGIN,
                OutPreClientBridgeSessionHandler(player, bridge, handler.supportConfig)
            )
            bridge.bindSession(this)
            owner.trace(
                "beginInitialJoin connect-bridge channel=${player.getChannel().id()} player=${player.username} ${
                    owner.describeState(state)
                }"
            )
            bridge.connect().whenCompleteAsync({ _, throwable ->
                if (throwable != null) {
//                    报错 error
                    error(throwable){ "OutPreBridge connect failed: ${throwable.message}" }
                    val messages = HyperZoneLoginMain.getInstance().messageService
                    owner.clearInitialSession(player.getChannel(), state)
                    hyperPlayer.resumeMessageDelivery()
                    player.sendMessage(
                        messages.render(
                            player,
                            MessageKeys.BackendAuth.ENTER_FAILED_EXCEPTION,
                            HyperZoneMessagePlaceholder.text("reason", throwable.message ?: "Unknown error"),
                        )
                    )
                    player.disconnect(Component.text("OutPre auth backend connection failed", NamedTextColor.RED))
                    return@whenCompleteAsync
                }
            }, player.connection.eventLoop())
        }
    }

    override fun onJoined() {
        val serverConn = velocityServerConnection ?: return
        NettyReflectionHelper.setConnectionInFlight(player, null)
        VelocityInternalAccess.setConnectedServer(player, serverConn)
        serverConn.completeJoin()
        boundOwner?.let { onAuthServerJoined(it) }
    }

    override fun release(owner: OutPreVServerAuth, handler: OutPreAuthSessionHandler, preferredTargetServerName: String?) {
        owner.closeInitialSession(player)
        val clientHandler = handler.mcConnection.activeSessionHandler as? OutPreClientBridgeSessionHandler
            ?: throw IllegalStateException("no Required clientHandler for bridge release")
        clientHandler.releaseToVelocity(handler.server) {
            handler.onReleased(preferredTargetServerName)
        }
    }

    override fun onDisconnected(reason: String?) {
        val owner = boundOwner ?: return
        val wasPresent = owner.clearInitialSession(player.getChannel(), state)
        if (player.isActive) {
            player.disconnect(Component.text(reason ?: "OutPre auth bridge disconnected", NamedTextColor.RED))
        }
        if (wasPresent) {
            logger.warn(
                "OutPre initial backend bridge disconnected before verification: player={}, reason={}",
                player.username,
                reason
            )
        }
    }

    override fun onVerified(owner: OutPreVServerAuth) {
        if (!state.hasConnectedToAuthServerOnce) {
            state.verifiedExitPending = true
            owner.trace(
                "outpre.onVerified deferred-until-auth-join channel=${player.getChannel().id()} player=${player.username} ${
                    owner.describeState(state)
                }"
            )
            return
        }
        owner.continueVerifiedInitialJoin(player, handler, state)
    }

    override fun onAuthServerJoined(owner: OutPreVServerAuth) {
        state.hasConnectedToAuthServerOnce = true
        owner.trace(
            "outpre.onAuthServerJoined channel=${player.getChannel().id()} player=${player.username} attachedProfile=${hyperPlayer.hasAttachedProfile()} ${
                owner.describeState(state)
            }"
        )
        hyperPlayer.resumeMessageDelivery()
        if (shouldPublishAuthStartAfterJoin) {
            owner.publishAuthStartEvent(player, hyperPlayer, state, "onAuthServerJoined")
        }
        owner.publishWaitingAreaJoinEvent(player, hyperPlayer)

        if (state.verifiedExitPending) {
            state.verifiedExitPending = false
            owner.trace(
                "outpre.onAuthServerJoined consume-verifiedExitPending channel=${player.getChannel().id()} player=${player.username} ${
                    owner.describeState(state)
                }"
            )
            owner.continueVerifiedInitialJoin(player, handler, state)
        }
    }
}
