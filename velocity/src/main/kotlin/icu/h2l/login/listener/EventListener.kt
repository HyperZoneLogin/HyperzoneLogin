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
import icu.h2l.api.connection.disconnectWithMessage
import icu.h2l.api.event.connection.OpenStartAuthEvent
import icu.h2l.api.event.connection.OpenPreLoginEvent
import icu.h2l.api.event.profile.ProfileResolveEvent
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

    @Subscribe(priority = Short.MIN_VALUE)
    fun onPreLoginChannelInit(event: OpenPreLoginEvent) {
        // Run last so other listeners can finish deciding the player's online/offline mode.
        HyperZonePlayerManager.create(event.channel, event.userName, event.uuid, event.isOnline)
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
    }
}