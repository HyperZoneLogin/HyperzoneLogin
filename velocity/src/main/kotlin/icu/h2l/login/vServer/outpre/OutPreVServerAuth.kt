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

package icu.h2l.login.vServer.outpre

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.server.VelocityRegisteredServer
import icu.h2l.api.event.area.PlayerAreaTransitionReason
import icu.h2l.api.event.vServer.VServerJoinEvent
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.player.getChannel
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.listener.PlayerAreaLifecycleListener
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.manager.LoginManager
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.player.VelocityHyperZonePlayer
import icu.h2l.login.util.ConnectMessageKeys
import icu.h2l.login.util.connectPlayerToServer
import icu.h2l.login.util.resolveVelocityInitialTargetServerName
import icu.h2l.login.vServer.outpre.handler.OutPreAuthSessionHandler
import icu.h2l.login.vServer.outpre.session.OutPreInitialJoinSession
import icu.h2l.login.vServer.outpre.session.OutPreInitialJoinSessionFactory
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.optionals.getOrNull

/**
 * outpre = 在正常 Velocity 注册前，先把客户端连接桥接到真实认证服；
 * 认证完成后再恢复真正的 Velocity 登录尾段。
 */
class OutPreVServerAuth(
    private val server: ProxyServer,
) : HyperZoneVServerAdapter {
    private val logger
        get() = HyperZoneLoginMain.getInstance().logger
    private val states = ConcurrentHashMap<Channel, OutPreState>()
    private val initialJoinSessions = ConcurrentHashMap<Channel, OutPreInitialJoinSession>()

    private fun clearInitialSession(channel: Channel) {
        initialJoinSessions.remove(channel)?.destroy()
        states.remove(channel)
    }

    internal fun clearInitialSession(channel: Channel, state: OutPreState): Boolean {
        initialJoinSessions.remove(channel)?.destroy()
        return states.remove(channel, state)
    }

    internal fun replaceInitialSession(channel: Channel, session: OutPreInitialJoinSession) {
        val previousSession = initialJoinSessions.put(channel, session)
        if (previousSession != null && previousSession !== session) {
            previousSession.destroy()
        }
    }

    internal fun getInitialSession(player: ConnectedPlayer): OutPreInitialJoinSession? =
        initialJoinSessions[player.getChannel()]

    var registeredServer: RegisteredServer? = null
        private set

    fun init(@Suppress("UNUSED_PARAMETER") plugin: Any) {
        val authAddress = configuredAuthAddress()
            ?: throw IllegalStateException("OutPre auth endpoint is not configured")
        val proxy = server as? com.velocitypowered.proxy.VelocityServer
            ?: throw IllegalStateException("OutPre requires VelocityServer runtime")
        val authTargetLabel = configuredAuthTargetLabel()

        // 检查服务器是否已经注册，如果已经存在则直接获取，避免重复注册
        val existingServer = proxy.getServer(authTargetLabel).getOrNull()
        if (existingServer == null) {
            val outPreServerInfo = ServerInfo(authTargetLabel, authAddress)
            server.registerServer(outPreServerInfo)
            registeredServer = proxy.getServer(authTargetLabel).getOrNull()
        } else {
            registeredServer = existingServer
        }
    }

    internal fun trace(message: String) {
        debug(HyperZoneDebugType.OUTPRE_TRACE, message)
    }

    internal fun describeState(state: OutPreState?): String {
        if (state == null) {
            return "state=null"
        }
        return "state(inAuthHold=${state.inAuthHold}, hasConnectedToAuthServerOnce=${state.hasConnectedToAuthServerOnce}, verifiedExitPending=${state.verifiedExitPending}, initialFlowPending=${state.initialFlowPending}, returnTarget=${state.returnTargetServerName}, authTarget=${state.authTargetLabel})"
    }

    override fun isEnabled(): Boolean {
        return configuredAuthAddress() != null
    }

    // ---- 桥接创建 / 初始化入口 ----

    fun createBridge(player: ConnectedPlayer): OutPreBackendBridge {
        val authAddress = configuredAuthAddress()
            ?: throw IllegalStateException("OutPre auth endpoint is not configured")
        val proxy = server as? com.velocitypowered.proxy.VelocityServer
            ?: throw IllegalStateException("OutPre requires VelocityServer runtime")
        return OutPreBackendBridge(
            proxy, authAddress, player,
            registeredServer as VelocityRegisteredServer
        )
    }

    fun beginInitialJoin(player: ConnectedPlayer, handler: OutPreAuthSessionHandler) {
        val messages = HyperZoneLoginMain.getInstance().messageService
        val hyperPlayer = getHyperPlayer(player) ?: return
        trace(
            "beginInitialJoin start channel=${
                player.getChannel().id()
            } player=${player.username} attachedProfile=${hyperPlayer.hasAttachedProfile()}"
        )

        runCatching {
            hyperPlayer.injectProxyPlayer(player)
        }.onFailure { throwable ->
            logger.debug("outpre 绑定代理玩家跳过: ${throwable.message}")
        }

        if (configuredAuthAddress() == null) {
            player.disconnect(messages.render(player, MessageKeys.BackendAuth.MISCONFIGURED_DISCONNECT))
            return
        }

        val outpreConfig = HyperZoneLoginMain.getCoreConfig().vServer.outpre
        val isStruck = outpreConfig.struckOnlineMode && hyperPlayer.isOnlinePlayer
        val session = OutPreInitialJoinSessionFactory.create(
            struck = isStruck,
            player = player,
            hyperPlayer = hyperPlayer,
            handler = handler,
            authTargetLabel = configuredAuthTargetLabel(),
        )
        val state = session.state
        states[player.getChannel()] = state
        initialJoinSessions[player.getChannel()] = session
        hyperPlayer.suspendMessageDelivery()
        trace(
            "beginInitialJoin state-created channel=${
                player.getChannel().id()
            } player=${player.username} struck=$isStruck ${
                describeState(state)
            }"
        )

        if (!player.isActive) {
            trace(
                "beginInitialJoin player-inactive channel=${
                    player.getChannel().id()
                } player=${player.username} clearing-initial-state"
            )
            clearInitialSession(player.getChannel(), state)
            hyperPlayer.resumeMessageDelivery()
            return
        }

        if (hyperPlayer.hasAttachedProfile()) {
            state.inAuthHold = false
            state.verifiedExitPending = true
            trace(
                "beginInitialJoin already-ready-after-authStart channel=${
                    player.getChannel().id()
                } player=${player.username} ${
                    describeState(
                        state
                    )
                }"
            )
        }
        session.begin(this)
    }

    internal fun closeInitialSession(player: ConnectedPlayer) {
        // 初始 outpre 会话已完成职责，清理映射并标记 initialFlowPending 结束。
        val session = initialJoinSessions.remove(player.getChannel()) ?: return
        session.destroy()
        session.state.initialFlowPending = false
    }

    fun resolveReleaseTarget(player: ConnectedPlayer, preferredTargetServerName: String?): RegisteredServer? {
        val authTargetLabel = states[player.getChannel()]?.authTargetLabel ?: configuredAuthTargetLabel()
        val resolvedTargetName = preferredTargetServerName
            ?.takeUnless { it.isBlank() || it.equals(authTargetLabel, ignoreCase = true) }
            ?.takeIf { server.getServer(it).isPresent }
            ?: resolveFallbackTargetServerName(player, authTargetLabel)
        return resolvedTargetName?.let { server.getServer(it).orElse(null) }
    }

    override fun reJoin(player: Player) {
        val messages = HyperZoneLoginMain.getInstance().messageService
        val hyperPlayer = getHyperPlayer(player) ?: return
        if (configuredAuthAddress() == null) {
            player.sendMessage(messages.render(player, MessageKeys.BackendAuth.NO_AUTH_SERVER))
            return
        }

        HyperZoneLoginMain.getInstance().loginManager.dispatch(
            proxyPlayer = player,
            hyperZonePlayer = hyperPlayer,
            metadata = mapOf(
                "source" to "outpre-rejoin",
                "mode" to "outpre",
            )
        )
        if (hyperPlayer.hasAttachedProfile()) {
            return
        }

        logger.warn("OutPre reJoin is not available for direct auth endpoint mode: player={}", player.username)
        player.sendMessage(messages.render(player, MessageKeys.HzlCommand.AUTH_FLOW_UNAVAILABLE))
    }

    override fun isPlayerInWaitingArea(player: Player): Boolean {
        val state = states[player.getChannel()]
        return state != null && (
                state.inAuthHold ||
                        !state.hasConnectedToAuthServerOnce ||
                        state.initialFlowPending
                )
    }

    override fun supportsProxyFallbackCommands(): Boolean {
        return true
    }

    override fun allowsProxyFallbackCommand(player: Player): Boolean {
        return states.containsKey(player.getChannel())
    }

    override fun exitWaitingArea(player: Player): Boolean {
        val state = states[player.getChannel()] ?: return false
        if (state.initialFlowPending) {
            player.disconnect(Component.translatable("disconnect.closed"))
            return true
        }

        PlayerAreaLifecycleListener.markWaitingAreaLeavePending(player, PlayerAreaTransitionReason.EXIT_REQUEST)
        return connectPlayerToServer(player, server, resolveTargetServerName(player, state), ConnectMessageKeys.EXIT)
    }

    override fun onVerified(player: Player) {
        val state = states[player.getChannel()] ?: return
        val session = initialJoinSessions[player.getChannel()]
        trace(
            "outpre.onVerified before channel=${player.getChannel().id()} player=${player.username} ${
                describeState(
                    state
                )
            }"
        )
        state.inAuthHold = false

        if (state.initialFlowPending && session != null) {
            session.onVerified(this)
            return
        }

        if (!state.hasConnectedToAuthServerOnce) {
            state.verifiedExitPending = true
            trace(
                "outpre.onVerified deferred-no-auth-join channel=${
                    player.getChannel().id()
                } player=${player.username} ${
                    describeState(
                        state
                    )
                }"
            )
            return
        }

        trace(
            "outpre.onVerified connect-verified-target channel=${player.getChannel().id()} player=${player.username} ${
                describeState(
                    state
                )
            }"
        )
        connectVerifiedPlayerToResolvedTarget(player, state)
    }

    // ---- 运行时事件 ----

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        val player = event.player
        val messages = HyperZoneLoginMain.getInstance().messageService
        val state = states[player.getChannel()] ?: return
        if (!needsAuthServerProtection(state)) return

        val requestedServerName = event.originalServer.serverInfo.name
        if (rememberRequestedServerDuringAuth()) {
            state.returnTargetServerName = requestedServerName
        }

        player.sendMessage(messages.render(player, MessageKeys.BackendAuth.MUST_VERIFY_BEFORE_TRANSFER))
        event.result = ServerPreConnectEvent.ServerResult.denied()
    }

    @Subscribe
    fun onServerConnected(event: ServerPostConnectEvent) {
        val player = event.player
        val state = states[player.getChannel()] ?: return
        if (!state.inAuthHold && !state.initialFlowPending) {
            clearInitialSession(player.getChannel(), state)
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        clearInitialSession(event.player.getChannel())
    }


    // ---- 模式会话协作 ----

    /**
     * 向等待区链路广播"已经进入认证服/等待区"的加入事件。
     */
    internal fun publishWaitingAreaJoinEvent(player: Player, hyperPlayer: VelocityHyperZonePlayer) {
        server.eventManager.fire(VServerJoinEvent(player, hyperPlayer))
    }

    /**
     * 向认证模块广播“现在允许以等待区视角接管认证”的开始事件。
     */
    internal fun publishAuthStartEvent(
        player: Player,
        hyperPlayer: VelocityHyperZonePlayer,
        state: OutPreState,
        source: String
    ): CompletableFuture<LoginManager.Result> {
        val dispatchFuture = HyperZoneLoginMain.getInstance().loginManager.dispatch(
            proxyPlayer = player,
            hyperZonePlayer = hyperPlayer,
            metadata = mapOf(
                "source" to source,
                "mode" to "outpre",
                "authTarget" to state.authTargetLabel,
                "initialFlowPending" to state.initialFlowPending.toString(),
                "struck" to (!state.inAuthHold && state.hasConnectedToAuthServerOnce).toString(),
            )
        )
        dispatchFuture.whenCompleteAsync({ result, throwable ->
            if (throwable != null) {
                trace(
                    "$source login-dispatch-error channel=${player.getChannel().id()} player=${player.username} error=${throwable.message}"
                )
                return@whenCompleteAsync
            }
            val resolved = result ?: LoginManager.Result(
                status = LoginManager.Status.INTERNAL_ERROR,
                reason = "dispatch returned null"
            )
            trace(
                "$source after-login-dispatch channel=${player.getChannel().id()} player=${player.username} attachedProfile=${hyperPlayer.hasAttachedProfile()} result=${resolved.status} winner=${resolved.winnerModuleId} failures=${resolved.failures.size} ${describeState(state)}"
            )
        }, player.getChannel().eventLoop())
        return dispatchFuture
    }

    /**
     * 处理“初始进入阶段已完成验证”后的后续动作：
     * - 如果仍处于初始登录桥接期，就继续完成 outpre 释放；
     * - 否则按已验证玩家直接转发到目标服。
     */
    internal fun continueVerifiedInitialJoin(
        player: Player,
        handler: OutPreAuthSessionHandler,
        state: OutPreState
    ) {
        trace(
            "outpre.onVerified completing-initial-flow channel=${
                player.getChannel().id()
            } player=${player.username} target=${state.returnTargetServerName} ${
                describeState(
                    state
                )
            }"
        )
        if (state.initialFlowPending) {
            handler.completeAfterVerification(state.returnTargetServerName)
        } else {
            connectVerifiedPlayerToResolvedTarget(player, state)
        }
    }

    // ---- 目标服转发 ----

    private fun connectVerifiedPlayerToResolvedTarget(player: Player, state: OutPreState): Boolean {
        clearInitialSession(player.getChannel(), state)
        return connectPlayerToServer(player, server, resolveTargetServerName(player, state), ConnectMessageKeys.VERIFIED)
    }

    // ---- 状态 / 配置辅助 ----

    private fun needsAuthServerProtection(state: OutPreState): Boolean {
        return state.inAuthHold || !state.hasConnectedToAuthServerOnce || state.initialFlowPending
    }

    private fun getHyperPlayer(player: Player): VelocityHyperZonePlayer? {
        return HyperZonePlayerManager.getByPlayerOrNull(player)
    }

    private fun configuredAuthAddress(): java.net.InetSocketAddress? {
        return HyperZoneLoginMain.getCoreConfig().vServer.outpre.resolveOutpreAuthAddress()
    }

    private fun configuredAuthTargetLabel(): String {
        return HyperZoneLoginMain.getCoreConfig().vServer.outpre.outpreAuthTargetLabel()
    }

    private fun rememberRequestedServerDuringAuth(): Boolean {
        return HyperZoneLoginMain.getCoreConfig().vServer.rememberRequestedServerDuringAuth
    }

    private fun resolveTargetServerName(player: Player, state: OutPreState): String? =
        state.returnTargetServerName
            ?.takeUnless { it.isBlank() || it.equals(state.authTargetLabel, ignoreCase = true) }
            ?: resolveFallbackTargetServerName(player, state.authTargetLabel)

    private fun resolveFallbackTargetServerName(player: Player, authServerName: String): String? {
        return resolveVelocityInitialTargetServerName(server, player, authServerName)
    }

}
