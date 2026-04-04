package icu.h2l.login.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.connection.client.InitialInboundConnection
import icu.h2l.api.connection.getInitialChannel
import icu.h2l.api.event.connection.OnlineAuthEvent
import icu.h2l.api.event.connection.OpenPreLoginEvent
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.type.OfflineUUIDType
import icu.h2l.login.util.ExtraUuidUtils
import icu.h2l.login.util.info
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class EventListener {
    companion object {
        const val EXPECTED_NAME_PREFIX = RemapUtils.EXPECTED_NAME_PREFIX
        const val REMAP_PREFIX = RemapUtils.REMAP_PREFIX
        private const val PLUGIN_CONFLICT_MESSAGE = "登录失败：检测到插件冲突。"
    }

    @Subscribe
    fun onPreLogin(event: OpenPreLoginEvent) {
        val uuid = event.uuid
        val name = event.userName
        val host = event.host

        if (!HyperZoneLoginMain.getOfflineMatchConfig().enable) return

        val offlineHost = HyperZoneLoginMain.getInstance().loginServerManager.shouldOfflineHost(host)
        if (offlineHost) {
            info { "匹配到离线 host 玩家: $name" }
        }
        val offlineUUIDType = ExtraUuidUtils.matchType(uuid, name)

        if (offlineUUIDType != OfflineUUIDType.UNKNOWN || offlineHost) {
            event.isOnline = false
        } else {
            event.isOnline = true
        }
        HyperZonePlayerManager.create(event.channel, event.userName, event.uuid)
        info { "传入 UUID 信息玩家: $name UUID:$uuid 类型: $offlineUUIDType 在线:${event.isOnline}" }
    }

    @Subscribe
    fun onOnlineAuth(event: OnlineAuthEvent) {
        event.gameProfile = RemapUtils.randomProfile()
    }


    @Subscribe
    fun onPreLogin(event: GameProfileRequestEvent) {
        val incomingProfile = event.gameProfile
        val incomingName = incomingProfile.name
        fun disconnectWithError(logMessage: String, userMessage: String) {
            HyperZoneLoginMain.getInstance().logger.error(logMessage)
            (event.connection as InitialInboundConnection).disconnect(
                Component.text(userMessage, NamedTextColor.RED)
            )
        }

        if (!incomingName.startsWith(EXPECTED_NAME_PREFIX)) {
            disconnectWithError(
                "GameProfile 名称校验失败：$incomingName (期望前缀 $EXPECTED_NAME_PREFIX)，疑似插件冲突",
                PLUGIN_CONFLICT_MESSAGE
            )
            return
        }

        val expectedUuid = RemapUtils.genUUID(incomingName, REMAP_PREFIX)
        if (incomingProfile.id != expectedUuid) {
            disconnectWithError(
                "GameProfile UUID 校验失败：name=$incomingName actual=${incomingProfile.id} expected=$expectedUuid，疑似插件冲突",
                PLUGIN_CONFLICT_MESSAGE
            )
            return
        }

        val hyperZonePlayer = HyperZonePlayerManager.getByChannel(event.connection.getInitialChannel())
        val originalProfile = event.originalProfile

        val resolvedProfile = hyperZonePlayer.getProfile()
        if (resolvedProfile == null) {
            disconnectWithError(
                "玩家 ${event.gameProfile.name} 在 GameProfileRequest 阶段未找到 Profile，已拒绝连接",
                "登录失败：未找到你的档案信息，请联系管理员。"
            )
            return
        }

        event.gameProfile = GameProfile(
            resolvedProfile.uuid,
            resolvedProfile.name,
            originalProfile.properties,
        )
    }
}