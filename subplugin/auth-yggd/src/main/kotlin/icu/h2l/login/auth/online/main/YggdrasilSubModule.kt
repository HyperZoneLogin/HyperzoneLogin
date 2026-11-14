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

package icu.h2l.login.auth.online.main

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.UserTable
import icu.h2l.api.message.HyperZoneModuleMessageResources
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.profile.HyperZoneUserServiceProvider
import icu.h2l.api.profile.UserMergeHandler
import icu.h2l.api.profile.UserMergeHandlerRegistry
import icu.h2l.login.auth.online.config.YggdrasilConfigLoader
import icu.h2l.login.auth.online.config.YggdrasilDebug
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import icu.h2l.login.auth.online.db.EntryTableManager
import icu.h2l.login.auth.online.listener.AuthListener
import icu.h2l.login.auth.online.listener.YggdrasilProfileLookupListener
import icu.h2l.login.auth.online.manager.EntryConfigManager
import icu.h2l.login.auth.online.manager.YggdrasilAuthModule
import icu.h2l.login.auth.online.util.ChannelIdUtil
import java.util.*

class YggdrasilSubModule : HyperSubModule {
    lateinit var entryConfigManager: EntryConfigManager
    lateinit var entryTableManager: EntryTableManager
    lateinit var yggdrasilAuthModule: YggdrasilAuthModule

    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy
        val dataDirectory = api.dataDirectory
        val databaseManager: HyperZoneDatabaseManager = api.databaseManager
        HyperZoneModuleMessageResources.copyBundledLocales(dataDirectory, "auth-yggd", javaClass.classLoader)

        val moduleConfig = YggdrasilConfigLoader.load(dataDirectory)
        YggdrasilDebug.configure(moduleConfig.debug)

        val entryConfigManager = EntryConfigManager(dataDirectory, proxy)
        val entryTableManager = EntryTableManager(
            databaseManager = databaseManager,
            tablePrefix = databaseManager.tablePrefix,
            userTable = UserTable(databaseManager.tablePrefix)
        )

        proxy.eventManager.register(api, entryTableManager)

        entryConfigManager.loadAllConfigs()

        entryConfigManager.getAllConfigs().forEach { (_, entry) ->
            api.credentialChannelRegistry.registerChannel(ChannelIdUtil.getChannelId(entry.id))
        }

        val userService = HyperZoneUserServiceProvider.get()
        val entryDatabaseHelper = EntryDatabaseHelper(
            databaseManager = databaseManager,
            entryTableManager = entryTableManager,
        )
        UserMergeHandlerRegistry.register(object : UserMergeHandler {
            override val handlerId: String = "auth-yggd"

            override fun migrateUserBindings(sourceUserUUID: UUID, targetUserUUID: UUID) {
                if (!entryDatabaseHelper.migrateUserBindings(sourceUserUUID, targetUserUUID)) {
                    throw IllegalStateException("Yggdrasil 入口绑定迁移失败")
                }
            }
        })

        val yggdrasilAuthModule = YggdrasilAuthModule(
            proxy = proxy,
            entryConfigManager = entryConfigManager,
            databaseManager = databaseManager,
            entryTableManager = entryTableManager,
            entryDatabaseHelper = entryDatabaseHelper,
            userService = userService,
            playerAccessor = api.hyperZonePlayers
        )
        val authListener = AuthListener(yggdrasilAuthModule)

        proxy.eventManager.register(api, authListener)
        proxy.eventManager.register(
            api,
            YggdrasilProfileLookupListener(
                entryConfigManager = entryConfigManager,
                entryDatabaseHelper = entryDatabaseHelper,
                userService = userService,
            )
        )

        this.entryConfigManager = entryConfigManager
        this.entryTableManager = entryTableManager
        this.yggdrasilAuthModule = yggdrasilAuthModule

    }
}
