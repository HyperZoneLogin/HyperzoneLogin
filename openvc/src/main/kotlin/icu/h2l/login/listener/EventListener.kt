package icu.h2l.login.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.connection.disconnectWithMessage
import icu.h2l.api.connection.getNettyChannel
import icu.h2l.api.event.connection.OnlineAuthEvent
import icu.h2l.api.event.connection.OpenPreLoginEvent
import icu.h2l.api.event.profile.ProfileSkinApplyEvent
import icu.h2l.api.log.error
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class EventListener {
    companion object {
        const val EXPECTED_NAME_PREFIX = RemapUtils.EXPECTED_NAME_PREFIX
        const val REMAP_PREFIX = RemapUtils.REMAP_PREFIX
        private const val PLUGIN_CONFLICT_MESSAGE = "登录失败：检测到插件冲突。"
    }

    // OpenPreLogin handling has been moved to the auth-offline module to centralize offline matching.

    @Subscribe
    fun onPreLoginChannelInit(event: OpenPreLoginEvent) {
        // channel/player initialization belongs to core. Keep this call here to guarantee
        // HyperZonePlayerManager state exists before other listeners (e.g. auth-offline) run.
        HyperZonePlayerManager.create(event.channel, event.userName, event.uuid)
    }

    @Subscribe
    fun onOnlineAuth(event: OnlineAuthEvent) {
        val hyperZonePlayer = HyperZonePlayerManager.getByChannel(event.channel) as? icu.h2l.login.player.OpenVcHyperZonePlayer
        hyperZonePlayer?.recordOnlineAuthIdentity(event.userName, event.userUUID)

        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) return
//        进行档案强制性替换
        event.gameProfile = RemapUtils.randomProfile()
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

//        新玩家在此阶段正常找不到profile
        val hyperZonePlayer = HyperZonePlayerManager.getByChannel(event.connection.getNettyChannel())

//        对新玩家不处理皮肤流程
        if(hyperZonePlayer.canRegister()) return

        val baseProfile = hyperZonePlayer.getGameProfile()
        val applyEvent = ProfileSkinApplyEvent(hyperZonePlayer, baseProfile)
        runCatching {
            HyperZoneLoginMain.getInstance().proxy.eventManager.fire(applyEvent).join()
        }.onFailure { throwable ->
            error(throwable) { "Profile skin apply event failed: ${throwable.message}" }
        }

        val textures = applyEvent.textures
        event.gameProfile = if (textures == null) {
            baseProfile
        } else {
            GameProfile(
                baseProfile.id,
                baseProfile.name,
                baseProfile.properties
                    .filterNot { it.name.equals("textures", ignoreCase = true) }
                    .toMutableList()
                    .apply { add(textures.toProperty()) }
            )
        }
    }
}