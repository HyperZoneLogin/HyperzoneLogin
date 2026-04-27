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

package icu.h2l.login.auth.floodgate.listener

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.event.profile.LoginProfileReplaceEvent
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug

/**
 * Floodgate 渠道专用的 [LoginProfileReplaceEvent] 处理器（[PostOrder.LAST]）。
 *
 * 对于通过 Floodgate 渠道认证的基岩版玩家（[icu.h2l.api.player.HyperZonePlayer.authChannelId] == "floodgate"），
 * 该监听器会覆写事件中的转发档案：
 * - 处于等待区或连接目标为登录服时，使用临时档案；
 * - 否则使用 [icu.h2l.api.player.HyperZonePlayer.getApplyGameProfile] 解析的正式档案（含皮肤）。
 *
 * 以 [PostOrder.LAST] 运行，确保在默认处理器（[PostOrder.NORMAL]）之后执行，
 * 从而对 Floodgate 玩家的档案进行最终覆写。
 */
class FloodgateLoginProfileReplaceListener {

    @Subscribe(order = PostOrder.LAST)
    fun onLoginProfileReplace(event: LoginProfileReplaceEvent) {
        val hyperPlayer = event.hyperZonePlayer
        if (hyperPlayer.authChannelId != FLOODGATE_CHANNEL_ID) {
            return
        }

        val targetProfile: GameProfile = if (event.isLoginServerTarget || hyperPlayer.isInWaitingArea()) {
            hyperPlayer.getTemporaryGameProfile()
        } else {
            runCatching { hyperPlayer.getApplyGameProfile() }.getOrElse { throwable ->
                debug(HyperZoneDebugType.OUTPRE_TRACE) {
                    "[FloodgateProfileReplace] 获取正式档案失败，回退到初始档案: player=${hyperPlayer.clientOriginalName}, reason=${throwable.message}"
                }
                return
            } ?: run {
                debug(HyperZoneDebugType.OUTPRE_TRACE) {
                    "[FloodgateProfileReplace] getApplyGameProfile 返回 null，跳过覆写: player=${hyperPlayer.clientOriginalName}"
                }
                return
            }
        }

        val velocityPlayer = hyperPlayer.getProxyPlayerOrNull() ?: return
        val currentVcProfile = velocityPlayer.gameProfile

        if (targetProfile.id == currentVcProfile.id &&
            targetProfile.name == currentVcProfile.name &&
            targetProfile.properties.isEmpty() && currentVcProfile.properties.isEmpty()
        ) {
            // 档案无实质差异，无需触发替换
            return
        }

        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "[FloodgateProfileReplace] 覆写转发档案: player=${hyperPlayer.clientOriginalName} name=${targetProfile.name} uuid=${targetProfile.id}"
        }
        event.profile = targetProfile
        event.modified = true
    }

    companion object {
        private const val FLOODGATE_CHANNEL_ID = "floodgate"
    }
}

