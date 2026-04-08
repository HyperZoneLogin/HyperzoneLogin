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
import icu.h2l.login.player.OpenVcHyperZonePlayer
import net.kyori.adventure.text.Component

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

        if (hyperPlayer.isVerified()) return

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
        hyperPlayer: OpenVcHyperZonePlayer,
        authServer: RegisteredServer,
        targetServerName: String?
    ): Boolean {
        hyperPlayer.update(player)
        hyperPlayer.beginBackendAuthHold(
            authServerName = authServer.serverInfo.name,
            targetServerName = if (rememberRequestedServerDuringAuth()) targetServerName else null
        )

        val authStartEvent = VServerAuthStartEvent(player, hyperPlayer)
        server.eventManager.fire(authStartEvent).join()
        if (authStartEvent.pass) {
            hyperPlayer.clearBackendAuthHold()
            return false
        }
        return true
    }

    private fun fireJoinIfNeeded(
        player: Player,
        hyperPlayer: OpenVcHyperZonePlayer,
        server: RegisteredServer
    ) {
        if (!hyperPlayer.markBackendAuthJoinHandled(server.serverInfo.name)) {
            return
        }

        this.server.eventManager.fire(VServerJoinEvent(player, hyperPlayer))
    }

    private fun getHyperPlayer(player: Player): OpenVcHyperZonePlayer? {
        return runCatching {
            HyperZonePlayerManager.getByPlayer(player) as OpenVcHyperZonePlayer
        }.getOrNull()
    }

    private fun configuredAuthServerName(): String {
        return HyperZoneLoginMain.getMiscConfig().fallbackAuthServer.trim()
    }

    private fun rememberRequestedServerDuringAuth(): Boolean {
        return HyperZoneLoginMain.getMiscConfig().rememberRequestedServerDuringAuth
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


