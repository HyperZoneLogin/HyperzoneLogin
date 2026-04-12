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

package icu.h2l.login.profile.skin

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.api.log.info
import icu.h2l.api.module.HyperSubModule
import icu.h2l.login.profile.skin.config.ProfileSkinConfigLoader
import icu.h2l.login.profile.skin.db.ProfileSkinCacheRepository
import icu.h2l.login.profile.skin.db.ProfileSkinCacheTable
import icu.h2l.login.profile.skin.db.ProfileSkinCacheTableManager
import icu.h2l.login.profile.skin.service.ProfileSkinSelfReplayService
import icu.h2l.login.profile.skin.service.ProfileSkinService
class ProfileSkinSubModule : HyperSubModule {
    lateinit var tableManager: ProfileSkinCacheTableManager
    lateinit var repository: ProfileSkinCacheRepository
    lateinit var service: ProfileSkinService
    lateinit var selfReplayService: ProfileSkinSelfReplayService

    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy
        val dataDirectory = api.dataDirectory
        val databaseManager: HyperZoneDatabaseManager = api.databaseManager
        val config = ProfileSkinConfigLoader.load(dataDirectory)
        val table = ProfileSkinCacheTable(
            prefix = databaseManager.tablePrefix,
            profileTable = ProfileTable(databaseManager.tablePrefix)
        )

        tableManager = ProfileSkinCacheTableManager(databaseManager, table)
        repository = ProfileSkinCacheRepository(databaseManager, table)
        service = ProfileSkinService(config, repository)
        selfReplayService = ProfileSkinSelfReplayService(api, config)

        tableManager.createTable()
        proxy.eventManager.register(api, tableManager)
        proxy.eventManager.register(api, service)
        proxy.eventManager.register(api, selfReplayService)

        info { "ProfileSkinSubModule 已加载，皮肤缓存、修复与 self replay 监听器已注册" }
    }
}

