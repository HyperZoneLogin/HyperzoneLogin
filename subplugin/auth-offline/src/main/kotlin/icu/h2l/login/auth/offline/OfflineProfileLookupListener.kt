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

package icu.h2l.login.auth.offline

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.db.InGameProfileCandidate
import icu.h2l.api.event.profile.InGameProfileLookupEvent
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.login.auth.offline.config.AuthOfflineConfigLoader
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import icu.h2l.login.auth.offline.util.ExtraUuidUtils

/**
 * 离线认证渠道反向查询监听器。
 */
class OfflineProfileLookupListener(
    private val repository: OfflineAuthRepository,
    private val userService: HyperZoneUserService,
) {
    @Subscribe
    fun onProfileChannelLookup(event: InGameProfileLookupEvent) {
        val entry = repository.getByProfileId(event.userUUID) ?: return
        val resolvedUuid = if (AuthOfflineConfigLoader.getConfig().main.passOfflineUuidToProfileResolve) {
            ExtraUuidUtils.getNormalOfflineUUID(entry.name)
        } else {
            userService.defaultGameUuid(entry.name)
        }
        event.contribute(
            InGameProfileCandidate(
                channel = "offline",
                name = entry.name,
                uuid = resolvedUuid,
                channelEntry = "",
                channelAccountId = entry.name,
            )
        )
    }
}
