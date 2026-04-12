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

package icu.h2l.login.auth.online

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.api.message.HyperZoneModuleMessageResources
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.profile.HyperZoneProfileServiceProvider
import icu.h2l.login.auth.online.db.EntryTableManager
import icu.h2l.login.auth.online.manager.EntryConfigManager

class YggdrasilSubModule : HyperSubModule {
    lateinit var entryConfigManager: EntryConfigManager
    lateinit var entryTableManager: EntryTableManager
    lateinit var yggdrasilAuthModule: YggdrasilAuthModule

    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy
        val dataDirectory = api.dataDirectory
        val databaseManager: HyperZoneDatabaseManager = api.databaseManager
        HyperZoneModuleMessageResources.copyBundledLocales(dataDirectory, "auth-yggd", javaClass.classLoader)

        val entryConfigManager = EntryConfigManager(dataDirectory, proxy)
        val entryTableManager = EntryTableManager(
            databaseManager = databaseManager,
            tablePrefix = databaseManager.tablePrefix,
            profileTable = ProfileTable(databaseManager.tablePrefix)
        )

        proxy.eventManager.register(api, entryTableManager)

        entryConfigManager.loadAllConfigs()

        val yggdrasilAuthModule = YggdrasilAuthModule(
            proxy = proxy,
            entryConfigManager = entryConfigManager,
            databaseManager = databaseManager,
            entryTableManager = entryTableManager,
            playerAccessor = api.hyperZonePlayers,
            profileService = HyperZoneProfileServiceProvider.get()
        )
        val yggdrasilEventListener = YggdrasilEventListener(yggdrasilAuthModule)

        proxy.eventManager.register(api, yggdrasilEventListener)

        this.entryConfigManager = entryConfigManager
        this.entryTableManager = entryTableManager
        this.yggdrasilAuthModule = yggdrasilAuthModule

    }
}
