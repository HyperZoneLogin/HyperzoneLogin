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

package icu.h2l.login.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.player.configuration.PlayerFinishConfigurationEvent
import icu.h2l.api.connection.disconnectWithMessage
import icu.h2l.api.event.connection.OpenStartAuthEvent
import icu.h2l.api.event.profile.ProfileSkinPreprocessEvent
import icu.h2l.api.event.connection.OpenPreLoginEvent
import icu.h2l.api.event.profile.ProfileResolveEvent
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.VelocityHyperZonePlayer
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

    @Subscribe(priority = Short.MAX_VALUE)
    fun onProfileSkinPreprocess(event: ProfileSkinPreprocessEvent) {
        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) {
            return
        }

        val velocityPlayer = event.hyperZonePlayer as? VelocityHyperZonePlayer ?: return
        val textures = event.textures ?: return

        /**
         * 这里在 `ProfileSkinPreprocessEvent` 高优先级阶段直接记录并尝试补发 self `ADD_PLAYER`：
         * 1. 该事件已经拿到了正版认证返回的最初皮肤；
         * 2. 我们先把整份 `ProfileSkinTextures` 原样记下来，供后续 configuration replay 复用；
         * 3. 如果此时玩家连接已经可写，就立刻发送一次 self `ADD_PLAYER`，不再依赖 Netty 拦截器。
         *
         * 注意：这不是通用资料同步入口，只是当前“客户端自皮肤修复”的专用补包点。
         */
        velocityPlayer.sendSelfAddPlayerFromPreprocess(textures)
    }

    @Subscribe
    fun onPlayerFinishConfiguration(event: PlayerFinishConfigurationEvent) {
        /**
         * 这里必须在 `PlayerFinishConfigurationEvent` 再补一次 self `ADD_PLAYER` 计划，
         * 原因不是服务端逻辑变化，而是 vanilla 客户端自己的状态机会把之前那份 self `PlayerInfo` 丢掉。
         *
         * 当前问题链路是：
         * 1. 后端/代理触发 `ClientboundFinishConfigurationPacket`；
         * 2. 客户端配置阶段结束后，会重建新的 `ClientPacketListener`；
         * 3. 旧 listener 上持有的 `playerInfoMap` 以及本地缓存的 self `PlayerInfo` 会随之失效；
         * 4. 这意味着我们在更早阶段补进去的 self `ADD_PLAYER`，即使当时已经生效，
         *    也可能在 finish configuration 之后被客户端侧状态重建“吃掉”；
         * 5. 结果就是：登录阶段明明已经补过皮肤，但客户端在 configuration 完成后又重新看不到自己皮肤。
         *
         * 因此这里的职责是：
         * - 把 `PlayerFinishConfigurationEvent` 视为“客户端 self PlayerInfo 可能已被重置”的信号；
         * - 基于最近一次缓存的 self 皮肤数据，重新直接补发一次 self `ADD_PLAYER`；
         * - 让客户端在新的 `ClientPacketListener` 生命周期里，再拿到一份属于自己的 `PlayerInfo`。
         *
         * 注意：
         * 1. 这里不是通用重同步逻辑，只针对 self 皮肤修复；
         * 2. 这里也不是重新改身份，而是把已经确定好的 `clientSendUUID` / `clientSendName`
         *    与最近一次可用 `textures` 再发一遍；
         * 3. 只有开启 `enableReplaceGameProfile` 时才需要做这件事。
         */
        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) {
            return
        }

        val velocityPlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(event.player())
        }.getOrNull() as? VelocityHyperZonePlayer ?: return

        // configuration 完成后，客户端可能已经换了一套新的 PlayerInfo 生命周期，
        // 所以这里显式再补发一次 self `ADD_PLAYER`，而不是依赖更早阶段的补包结果继续存在。
        velocityPlayer.replaySelfAddPlayerAfterConfigurationFinish()
    }

    @Subscribe
    fun onProfileResolve(event: ProfileResolveEvent) {
        val databaseHelper = HyperZoneLoginMain.getInstance().databaseHelper

        event.profileIdHint?.let { profileId ->
            val profile = databaseHelper.getProfile(profileId)
            if (profile == null) {
                event.deny("未找到指定的 Profile: $profileId")
            } else {
                event.resolve(profile)
            }
            return
        }

        val trustedName = event.trustedName
        val trustedUuid = event.trustedUuid
        if (trustedName.isNullOrBlank() || trustedUuid == null) {
            event.deny("缺少可信的 Profile 解析参数")
            return
        }

        val resolved = if (event.allowCreate) {
            databaseHelper.resolveOrCreateTrustedProfile(trustedName, trustedUuid)
        } else {
            databaseHelper.resolveTrustedProfile(trustedName, trustedUuid)
        }

        val profile = resolved.profile
        if (profile != null) {
            event.resolve(profile, resolved.created)
        } else {
            event.deny(resolved.reason ?: "Profile 解析失败")
        }
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
        }.setTemporaryGameProfile(randomProfile)
    }

    @Subscribe
    fun onGameProfileRequestEvent(event: GameProfileRequestEvent) {
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
    }
}