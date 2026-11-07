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
import icu.h2l.api.connection.getNettyChannel
import icu.h2l.api.event.profile.VerifyInitialGameProfileEvent
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.util.buildAttachedIdentityGameProfile
import icu.h2l.login.util.hasSemanticGameProfileDifference

/**
 * 当当前登录态已经 attach 正式档案时，
 * 核心应允许与该正式档案语义一致的初始 GameProfile 通过校验。
 */
class AttachedUserInitialGameProfileListener {
    @Subscribe
    fun onVerifyInitialGameProfileEvent(event: VerifyInitialGameProfileEvent) {
        val channel = event.connection.getNettyChannel()
        val hyperPlayer = HyperZonePlayerManager.getByChannelOrNull(channel)
            ?: return
        val attachedUser = hyperPlayer.attachedUser
            ?: return
        val activeIdentity = hyperPlayer.activeInGameProfile
            ?: return
        val expectedProfile = buildAttachedIdentityGameProfile(
            currentGameProfile = event.gameProfile,
            activeIdentity = activeIdentity,
        )
        if (hasSemanticGameProfileDifference(expectedProfile, event.gameProfile)) {
            return
        }

        event.pass = true
    }
}




