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

package icu.h2l.login.vServer.backend

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import icu.h2l.api.event.vServer.VServerAuthStartEvent
import icu.h2l.api.event.vServer.VServerJoinEvent
import icu.h2l.api.player.getChannel
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.VelocityHyperZonePlayer
import net.kyori.adventure.text.Component
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fallback auth hold flow used when Limbo is unavailable.
 *
 * Players are redirected to a configured real backend server, kept there until
 * verification succeeds, then automatically connected to their remembered target.
 */
class BackendAuthHoldListener(
    private val server: ProxyServer
) : HyperZoneVServerAdapter {
    /**
     * backend 等待服自己的私有会话状态。
     *
     * 这里同时保存：
     * 1. 当前是否仍处于 auth hold；
     * 2. 进入等待区前（或认证期间最新记住）的返回目标服。
     *
     * `returnTargetServerName` 不能在 `onVerified()` 时直接丢掉，
     * 因为玩家完成认证后仍可能再次停留/回到等待区服，此时 `/exit` 应优先把玩家送回
     * `authPlayer(...)` 将其转入等待区前的目标服，而不是直接断开连接。
     */
    private data class BackendHoldState(
        var authServerName: String,
        var returnTargetServerName: String? = null,
        var inAuthHold: Boolean = true,
        var joinAnnounced: Boolean = false
    )

    private val logger
        get() = HyperZoneLoginMain.getInstance().logger
    private val backendHoldStates = ConcurrentHashMap<io.netty.channel.Channel, BackendHoldState>()

    override fun isEnabled(): Boolean {
        return configuredAuthServerName().isNotBlank()
    }

    override fun authPlayer(player: Player) {
        val hyperPlayer = getHyperPlayer(player) ?: return
        val authServer = resolveAuthServer() ?: run {
            player.sendPlainMessage("§c当前未配置可用的认证等待服务器")
            return
        }

        val currentServerName = player.currentServer
            .map { it.server.serverInfo.name }
            .orElse(null)

        val preferredTarget = currentServerName
            ?.takeUnless { it.equals(authServer.serverInfo.name, ignoreCase = true) }

        if (!startAuthHold(player, hyperPlayer, authServer, preferredTarget)) {
            return
        }

        if (currentServerName.equals(authServer.serverInfo.name, ignoreCase = true)) {
            fireJoinIfNeeded(player, hyperPlayer, authServer)
            return
        }

        player.createConnectionRequest(authServer).connect().whenComplete { result, throwable ->
            if (throwable != null) {
                player.sendPlainMessage("§c进入认证等待服务器失败：${throwable.message ?: "未知错误"}")
                return@whenComplete
            }

            if (result == null || !result.isSuccessful) {
                val reason = result?.reasonComponent?.map { it.toString() }?.orElse("未知原因") ?: "未知原因"
                player.sendPlainMessage("§c进入认证等待服务器失败：$reason")
            }
        }
    }

    override fun isPlayerInWaitingArea(player: Player): Boolean {
        return isOnBackendAuthServer(player)
    }

    @Subscribe
    fun onInitialServerChoose(event: PlayerChooseInitialServerEvent) {
        if (!isEnabled()) return

        val player = event.player
        val hyperPlayer = getHyperPlayer(player) ?: return
        hyperPlayer.update(player)

        if (!hyperPlayer.isInWaitingArea()) return

        val authServer = resolveAuthServer() ?: run {
            player.disconnect(Component.text("§c认证等待服务器未配置正确，请联系管理员"))
            return
        }
        val targetServerName = event.initialServer
            .map { it.serverInfo.name }
            .orElse(null)
            ?.takeUnless { it.equals(authServer.serverInfo.name, ignoreCase = true) }

        if (!startAuthHold(player, hyperPlayer, authServer, targetServerName)) {
            return
        }

        event.setInitialServer(authServer)
    }

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        val player = event.player
        val hyperPlayer = getHyperPlayer(player) ?: return
        hyperPlayer.update(player)

        if (!isInBackendAuthHold(player, hyperPlayer)) return

        val authServer = resolveAuthServer() ?: run {
            player.disconnect(Component.text("§c认证等待服务器不可用，请联系管理员"))
            return
        }

        val requestedServerName = event.originalServer.serverInfo.name
        val authServerName = authServer.serverInfo.name
        if (requestedServerName.equals(authServerName, ignoreCase = true)) {
            event.result = ServerPreConnectEvent.ServerResult.allowed(authServer)
            return
        }

        if (rememberRequestedServerDuringAuth()) {
            rememberPostAuthTarget(player, requestedServerName)
        }

        player.sendPlainMessage("§e请先完成认证，然后才能进入其他服务器")
        event.result = if (event.previousServer == null) {
            ServerPreConnectEvent.ServerResult.allowed(authServer)
        } else {
            ServerPreConnectEvent.ServerResult.denied()
        }
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        val player = event.player
        val hyperPlayer = getHyperPlayer(player) ?: return
        hyperPlayer.update(player)

        if (!isInBackendAuthHold(player, hyperPlayer)) return
        fireJoinIfNeeded(player, hyperPlayer, event.server)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        backendHoldStates.remove(event.player.getChannel())
    }

    private fun startAuthHold(
        player: Player,
        hyperPlayer: VelocityHyperZonePlayer,
        authServer: RegisteredServer,
        targetServerName: String?
    ): Boolean {
        val resolvedTarget = resolvePostAuthTarget(player, authServer, targetServerName)
        hyperPlayer.update(player)
        beginBackendAuthHold(player, authServer.serverInfo.name, resolvedTarget)

        val authStartEvent = VServerAuthStartEvent(player, hyperPlayer)
        server.eventManager.fire(authStartEvent).join()
        if (authStartEvent.pass) {
            clearBackendAuthHold(player)
            return false
        }
        return true
    }

    private fun resolvePostAuthTarget(
        player: Player,
        authServer: RegisteredServer,
        preferredTargetServerName: String?
    ): String? {
        val authServerName = authServer.serverInfo.name

        val directTarget = preferredTargetServerName
            ?.takeUnless { it.isBlank() || it.equals(authServerName, ignoreCase = true) }
            ?.takeIf { server.getServer(it).isPresent }
        if (directTarget != null) {
            return directTarget
        }

        val configuredDefaultTarget = HyperZoneLoginMain.getBackendServerConfig().postAuthDefaultServer
            .trim()
            .takeUnless { it.isBlank() || it.equals(authServerName, ignoreCase = true) }
            ?.takeIf { server.getServer(it).isPresent }
        if (configuredDefaultTarget != null) {
            return configuredDefaultTarget
        }

        val config = server.configuration
        val hostKey = player.virtualHost
            .map { it.hostString.lowercase(Locale.ROOT) }
            .orElse("")
        val forcedOrder = config.forcedHosts[hostKey].orEmpty()
        val connectionOrder = if (forcedOrder.isNotEmpty()) {
            forcedOrder
        } else {
            config.attemptConnectionOrder
        }

        connectionOrder.firstOrNull { candidate ->
            !candidate.equals(authServerName, ignoreCase = true) && server.getServer(candidate).isPresent
        }?.let { return it }

        return server.getAllServers()
            .firstOrNull { candidate -> !candidate.serverInfo.name.equals(authServerName, ignoreCase = true) }
            ?.serverInfo
            ?.name
    }

    private fun fireJoinIfNeeded(
        player: Player,
        hyperPlayer: VelocityHyperZonePlayer,
        server: RegisteredServer
    ) {
        if (!markBackendAuthJoinHandled(player, server.serverInfo.name)) {
            return
        }

        this.server.eventManager.fire(VServerJoinEvent(player, hyperPlayer))
    }

    override fun supportsProxyFallbackCommands(): Boolean {
        return true
    }

    override fun canUseProxyFallbackCommand(player: Player): Boolean {
        /**
         * 这里必须按“当前是否位于后端等待区服务器”判断，不能再复用一次性的 hold 状态。
         *
         * 原因是玩家在该服务器完成登录后，`overVerify()` 会清掉 hold；
         * 但玩家之后仍可能重新进入这台等待区服务器做登出、改密、换绑等操作。
         * 如果这里退回到 hold / waitingArea 判定，将来很容易再次把命令范围误收紧。
         */
        return isOnBackendAuthServer(player)
    }

    override fun exitWaitingArea(player: Player): Boolean {
        if (!isOnBackendAuthServer(player)) {
            return false
        }

        /**
         * 后端等待服和 Limbo 不同：
         * 玩家“退出等待区”不应被直接断开，而应尽量送回 `authPlayer(...)`
         * 把他转入等待区之前的目标服。
         *
         * 只有 Limbo 才是“断开 Limbo 会话 = 退出等待区”。
         */
        val state = backendHoldStates[player.getChannel()]
        val authServerName = state?.authServerName ?: configuredAuthServerName()
        val returnTarget = state?.returnTargetServerName
            ?: resolveFallbackTargetServerName(player, authServerName)

        return connectPlayerToTarget(
            player = player,
            targetServerName = returnTarget,
            authServerName = authServerName,
            missingTargetMessage = "§e当前没有可返回的目标服务器，无法退出认证等待区",
            failurePrefix = "退出认证等待区后返回目标服务器失败"
        )
    }

    override fun onVerified(player: Player) {
        val state = backendHoldStates[player.getChannel()] ?: return
        state.inAuthHold = false
        state.joinAnnounced = false

        connectPlayerToTarget(
            player = player,
            targetServerName = state.returnTargetServerName,
            authServerName = state.authServerName,
            missingTargetMessage = "§e认证完成，但当前没有可自动返回的目标服务器",
            failurePrefix = "认证完成后自动连接到目标服务器失败"
        )
    }

    private fun getHyperPlayer(player: Player): VelocityHyperZonePlayer? {
        return runCatching {
            HyperZonePlayerManager.getByPlayer(player) as VelocityHyperZonePlayer
        }.getOrNull()
    }

    private fun configuredAuthServerName(): String {
        return HyperZoneLoginMain.getBackendServerConfig().fallbackAuthServer.trim()
    }

    private fun rememberRequestedServerDuringAuth(): Boolean {
        return HyperZoneLoginMain.getBackendServerConfig().rememberRequestedServerDuringAuth
    }

    private fun beginBackendAuthHold(player: Player, authServerName: String, targetServerName: String?) {
        val channel = player.getChannel()
        val rememberedTarget = targetServerName?.takeUnless { it.isBlank() }
            ?: backendHoldStates[channel]?.returnTargetServerName

        backendHoldStates[channel] = BackendHoldState(
            authServerName = authServerName,
            returnTargetServerName = rememberedTarget,
            inAuthHold = true,
            joinAnnounced = false
        )
    }

    private fun isInBackendAuthHold(player: Player, hyperZonePlayer: VelocityHyperZonePlayer): Boolean {
        return hyperZonePlayer.isInWaitingArea() && (backendHoldStates[player.getChannel()]?.inAuthHold == true)
    }

    private fun rememberPostAuthTarget(player: Player, serverName: String?) {
        val resolved = serverName?.takeUnless { it.isBlank() } ?: return
        backendHoldStates.computeIfPresent(player.getChannel()) { _, existing ->
            existing.returnTargetServerName = resolved
            existing
        }
    }

    private fun markBackendAuthJoinHandled(player: Player, serverName: String): Boolean {
        var handled = false
        backendHoldStates.computeIfPresent(player.getChannel()) { _, existing ->
            if (!existing.inAuthHold || !existing.authServerName.equals(serverName, ignoreCase = true) || existing.joinAnnounced) {
                return@computeIfPresent existing
            }

            existing.joinAnnounced = true
            handled = true
            existing
        }
        return handled
    }

    private fun clearBackendAuthHold(player: Player) {
        backendHoldStates.remove(player.getChannel())
    }

    private fun isOnBackendAuthServer(player: Player): Boolean {
        val authServerName = configuredAuthServerName()
        if (authServerName.isBlank()) {
            return false
        }

        val currentServerName = player.currentServer
            .map { it.server.serverInfo.name }
            .orElse(null)
            ?: return false

        return currentServerName.equals(authServerName, ignoreCase = true)
    }

    private fun resolveFallbackTargetServerName(player: Player, authServerName: String): String? {
        val directConfiguredTarget = HyperZoneLoginMain.getBackendServerConfig().postAuthDefaultServer
            .trim()
            .takeUnless { it.isBlank() || it.equals(authServerName, ignoreCase = true) }
            ?.takeIf { server.getServer(it).isPresent }
        if (directConfiguredTarget != null) {
            return directConfiguredTarget
        }

        val config = server.configuration
        val hostKey = player.virtualHost
            .map { it.hostString.lowercase(Locale.ROOT) }
            .orElse("")
        val forcedOrder = config.forcedHosts[hostKey].orEmpty()
        val connectionOrder = if (forcedOrder.isNotEmpty()) {
            forcedOrder
        } else {
            config.attemptConnectionOrder
        }

        connectionOrder.firstOrNull { candidate ->
            !candidate.equals(authServerName, ignoreCase = true) && server.getServer(candidate).isPresent
        }?.let { return it }

        return server.getAllServers()
            .firstOrNull { candidate -> !candidate.serverInfo.name.equals(authServerName, ignoreCase = true) }
            ?.serverInfo
            ?.name
    }

    private fun connectPlayerToTarget(
        player: Player,
        targetServerName: String?,
        authServerName: String,
        missingTargetMessage: String,
        failurePrefix: String
    ): Boolean {
        val resolvedTarget = targetServerName
            ?.takeUnless { it.isBlank() || it.equals(authServerName, ignoreCase = true) }
            ?: resolveFallbackTargetServerName(player, authServerName)

        if (resolvedTarget == null) {
            player.sendPlainMessage(missingTargetMessage)
            return false
        }

        val target = server.getServer(resolvedTarget).orElse(null)
        if (target == null) {
            player.sendPlainMessage("§c$failurePrefix：目标服务器 $resolvedTarget 不存在")
            return false
        }

        player.createConnectionRequest(target).connect().whenComplete { result, throwable ->
            if (throwable != null) {
                player.sendPlainMessage("§c$failurePrefix：${throwable.message ?: "未知错误"}")
                return@whenComplete
            }

            if (result == null || !result.isSuccessful) {
                val reason = result?.reasonComponent?.map { component ->
                    component.toString()
                }?.orElse("未知原因") ?: "未知原因"
                player.sendPlainMessage("§c$failurePrefix：$reason")
            }
        }
        return true
    }

    private fun resolveAuthServer(): RegisteredServer? {
        val serverName = configuredAuthServerName()
        if (serverName.isBlank()) {
            return null
        }

        return server.getServer(serverName).orElseGet {
            logger.warn("Fallback auth server '{}' is configured but was not found in Velocity", serverName)
            null
        }
    }
}


