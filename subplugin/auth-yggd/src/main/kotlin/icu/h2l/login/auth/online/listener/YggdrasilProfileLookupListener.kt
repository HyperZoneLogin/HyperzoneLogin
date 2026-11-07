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

package icu.h2l.login.auth.online.listener

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.db.InGameProfileCandidate
import icu.h2l.api.event.profile.InGameProfileLookupEvent
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import icu.h2l.login.auth.online.manager.EntryConfigManager

/**
 * Yggdrasil 渠道反向查询监听器。
 *
 * 每个入口表都按 profile_id 反查，返回该账号在所有入口下的记录。
 * 当入口未透传 Yggdrasil UUID 时，使用核心默认 UUID 规则还原最终游戏 UUID。
 */
class YggdrasilProfileLookupListener(
    private val entryConfigManager: EntryConfigManager,
    private val entryDatabaseHelper: EntryDatabaseHelper,
    private val userService: HyperZoneUserService,
) {
    @Subscribe
    fun onProfileChannelLookup(event: InGameProfileLookupEvent) {
        entryConfigManager.getAllConfigs().values.forEach { config ->
            val entryId = config.id.lowercase()
            entryDatabaseHelper.findEntriesByProfileId(entryId, event.userUUID).forEach { record ->
                val resolvedUuid = if (config.yggdrasil.passYggdrasilUuidToProfileResolve) {
                    record.uuid
                } else {
                    userService.defaultGameUuid(record.name)
                }
                event.contribute(
                    InGameProfileCandidate(
                        channel = "yggdrasil",
                        name = record.name,
                        uuid = resolvedUuid,
                        channelEntry = entryId,
                        channelAccountId = record.uuid.toString(),
                    )
                )
            }
        }
    }
}
