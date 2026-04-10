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
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import icu.h2l.api.event.vServer.VServerAuthStartEvent
import icu.h2l.api.event.vServer.VServerJoinEvent
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.VelocityHyperZonePlayer
import net.kyori.adventure.text.Component
import java.util.Locale

/**
 * Fallback auth hold flow used when Limbo is unavailable.
 *
 * Players are redirected to a configured real backend server, kept there until
 * verification succeeds, then automatically connected to their remembered target.
 */
class BackendAuthHoldListener(
    private val server: ProxyServer
) {
    private val logger
        get() = HyperZoneLoginMain.getInstance().logger

    fun isEnabled(): Boolean {
        return configuredAuthServerName().isNotBlank()
    }

    fun authPlayer(player: Player) {
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

        if (!hyperPlayer.isInBackendAuthHold()) return

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
            hyperPlayer.rememberPostAuthTarget(requestedServerName)
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

        if (!hyperPlayer.isInBackendAuthHold()) return
        fireJoinIfNeeded(player, hyperPlayer, event.server)
    }

    private fun startAuthHold(
        player: Player,
        hyperPlayer: VelocityHyperZonePlayer,
        authServer: RegisteredServer,
        targetServerName: String?
    ): Boolean {
        val resolvedTarget = resolvePostAuthTarget(player, authServer, targetServerName)
        hyperPlayer.update(player)
        hyperPlayer.beginBackendAuthHold(
            authServerName = authServer.serverInfo.name,
            targetServerName = resolvedTarget
        )

        val authStartEvent = VServerAuthStartEvent(player, hyperPlayer)
        server.eventManager.fire(authStartEvent).join()
        if (authStartEvent.pass) {
            hyperPlayer.clearBackendAuthHold()
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
        if (!hyperPlayer.markBackendAuthJoinHandled(server.serverInfo.name)) {
            return
        }

        this.server.eventManager.fire(VServerJoinEvent(player, hyperPlayer))
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


