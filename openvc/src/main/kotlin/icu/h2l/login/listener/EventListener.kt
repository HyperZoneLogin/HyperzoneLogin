package icu.h2l.login.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import icu.h2l.api.connection.disconnectWithMessage
import icu.h2l.api.connection.getNettyChannel
import icu.h2l.api.event.connection.OpenStartAuthEvent
import icu.h2l.api.event.connection.OpenPreLoginEvent
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.ProfileSkinApplySupport
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class EventListener {
    companion object {
        const val EXPECTED_NAME_PREFIX = RemapUtils.EXPECTED_NAME_PREFIX
        const val REMAP_PREFIX = RemapUtils.REMAP_PREFIX
        private const val PLUGIN_CONFLICT_MESSAGE = "登录失败：检测到插件冲突。"
    }

    // OpenPreLogin handling has been moved to the auth-offline module to centralize offline matching.

    @Subscribe(priority = Short.MIN_VALUE)
    fun onPreLoginChannelInit(event: OpenPreLoginEvent) {
        // Run last so other listeners can finish deciding the player's online/offline mode.
        HyperZonePlayerManager.create(event.channel, event.userName, event.uuid, event.isOnline)
    }

    @Subscribe
    fun onStartAuth(event: OpenStartAuthEvent) {
        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) return
//        进行档案强制性替换
        val randomProfile = RemapUtils.randomProfile()
        event.gameProfile = randomProfile
        runCatching {
            HyperZonePlayerManager.getByChannel(event.channel)
        }.getOrElse {
            HyperZonePlayerManager.create(event.channel, event.userName, event.userUUID, event.isOnline)
        }.setTemporaryForwardingProfile(randomProfile)
    }

    @Subscribe
    fun onPreLogin(event: GameProfileRequestEvent) {
//            不进行后端转发的情况下要准许使用原有的
        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) return

        val incomingProfile = event.gameProfile
        val incomingName = incomingProfile.name
        fun disconnectWithError(logMessage: String, userMessage: String) {
            HyperZoneLoginMain.getInstance().logger.error(logMessage)
            event.connection.disconnectWithMessage(Component.text(userMessage, NamedTextColor.RED))
        }

        if (!incomingName.startsWith(EXPECTED_NAME_PREFIX)) {
            disconnectWithError(
                "GameProfile 名称校验失败：$incomingName (期望前缀 $EXPECTED_NAME_PREFIX)，疑似插件冲突",
                PLUGIN_CONFLICT_MESSAGE
            )
            return
        }

//        我们在前一阶段把档案做了强制替换
        val expectedUuid = RemapUtils.genUUID(incomingName, REMAP_PREFIX)
        if (incomingProfile.id != expectedUuid) {
            disconnectWithError(
                "GameProfile UUID 校验失败：name=$incomingName actual=${incomingProfile.id} expected=$expectedUuid，疑似插件冲突",
                PLUGIN_CONFLICT_MESSAGE
            )
            return
        }

//        这里不做自己看不见自己皮肤，假设我们可以在VC的tablist添加一个虚拟的玩家来使得我们能够显示
        runCatching {
            val hyperPlayer = HyperZonePlayerManager.getByChannel(event.connection.getNettyChannel())
            ProfileSkinApplySupport.apply(hyperPlayer)
        }.onSuccess { mergedProfile ->
            event.gameProfile = mergedProfile
        }.onFailure { throwable ->
            HyperZoneLoginMain.getInstance().logger.error("GameProfile 预登录皮肤修复失败: ${throwable.message}", throwable)
        }

    }
}