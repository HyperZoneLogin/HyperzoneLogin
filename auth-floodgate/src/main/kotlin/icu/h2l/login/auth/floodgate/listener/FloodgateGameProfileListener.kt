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

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.connection.disconnectWithMessage
import icu.h2l.api.connection.getNettyChannel
import icu.h2l.api.event.profile.VerifyInitialGameProfileEvent
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.login.auth.floodgate.service.FloodgateAuthService

class FloodgateGameProfileListener(
    private val authService: FloodgateAuthService
) {

    @Subscribe
    fun onVerifyInitialGameProfile(event: VerifyInitialGameProfileEvent) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "onVerifyInitialGameProfile channel=${event.connection.getNettyChannel()} profileName=${event.gameProfile.name} profileId=${event.gameProfile.id}"
        }
        // Floodgate 会跳过 HZL 自订的 OpenPreLogin/OpenStartAuth，
        // 所以必须在这里提前创建登录期 HyperZonePlayer 并记录渠道会话。
        when (
            val result = authService.acceptInitialProfile(
                channel = event.connection.getNettyChannel(),
                userName = event.gameProfile.name,
                userUUID = event.gameProfile.id
            )
        ) {
            FloodgateAuthService.VerifyResult.NotFloodgate -> {
                debug(HyperZoneDebugType.OUTPRE_TRACE) {
                    "onVerifyInitialGameProfile ignored channel=${event.connection.getNettyChannel()} profileName=${event.gameProfile.name}: not floodgate"
                }
                return
            }
            is FloodgateAuthService.VerifyResult.Failed -> {
                debug(HyperZoneDebugType.OUTPRE_TRACE) {
                    "onVerifyInitialGameProfile failed channel=${event.connection.getNettyChannel()} profileName=${event.gameProfile.name}"
                }
                event.connection.disconnectWithMessage(result.userMessage)
                return
            }
            FloodgateAuthService.VerifyResult.Accepted -> {
                event.pass = true
                debug(HyperZoneDebugType.OUTPRE_TRACE) {
                    "onVerifyInitialGameProfile accepted channel=${event.connection.getNettyChannel()} profileName=${event.gameProfile.name} pass=${event.pass}"
                }
            }
        }
    }
}

