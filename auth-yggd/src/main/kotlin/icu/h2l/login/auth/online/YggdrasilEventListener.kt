package icu.h2l.login.auth.online

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import icu.h2l.api.event.connection.OnlineAuthEvent
import icu.h2l.api.event.vServer.VServerJoinEvent
import icu.h2l.api.log.debug
import icu.h2l.api.player.getChannel
import io.netty.channel.Channel
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class YggdrasilEventListener(
    private val yggdrasilAuthModule: YggdrasilAuthModule
) {
    private val pendingContexts = ConcurrentHashMap<Channel, PendingAuthContext>()

    @Subscribe
    fun onOnlineAuth(event: OnlineAuthEvent) {
        if (!event.isOnline) return

        pendingContexts[event.channel] = PendingAuthContext(
            username = event.userName,
            uuid = event.userUUID,
            serverId = event.serverId,
            playerIp = event.playerIp
        )
        debug {
            "[YggdrasilFlow] OnlineAuthEvent 收到，等待 LimboSpawn 触发验证: addr=${event.channel}, user=${event.userName}"
        }
    }

    @Subscribe
    fun onLimboSpawn(event: VServerJoinEvent) {
        if (!event.proxyPlayer.isOnlineMode) return
        if (event.hyperZonePlayer.isVerified()) return

        val channel = event.proxyPlayer.getChannel()
        val pending = pendingContexts.remove(channel)
        if (pending == null) {
            debug { "[YggdrasilFlow] LimboSpawnEvent 未找到待验证上下文，跳过: addr=$channel" }
            return
        }

        val username = event.proxyPlayer.username
        debug { "[YggdrasilFlow] LimboSpawnEvent 收到，开始验证: user=$username" }
        yggdrasilAuthModule.startYggdrasilAuth(
            player = event.proxyPlayer,
            username = pending.username,
            uuid = pending.uuid,
            serverId = pending.serverId,
            playerIp = pending.playerIp
        )
        yggdrasilAuthModule.registerLimboHandler(event.proxyPlayer, event.hyperZonePlayer)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        pendingContexts.remove(event.player.getChannel())
        yggdrasilAuthModule.clearPlayerCacheOnDisconnect(event.player)
    }
}

private data class PendingAuthContext(
    val username: String,
    val uuid: UUID,
    val serverId: String,
    val playerIp: String?
)
