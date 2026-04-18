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

package icu.h2l.login.auth.floodgate

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.api.log.info
import icu.h2l.api.message.HyperZoneModuleMessageResources
import icu.h2l.api.module.HyperSubModule
import icu.h2l.login.auth.floodgate.config.FloodgateAuthConfigLoader
import icu.h2l.login.auth.floodgate.db.FloodgateAuthRepository
import icu.h2l.login.auth.floodgate.db.FloodgateAuthTableManager
import icu.h2l.login.auth.floodgate.listener.FloodgateGameProfileListener
import icu.h2l.login.auth.floodgate.listener.FloodgateOpenStartAuthListener
import icu.h2l.login.auth.floodgate.listener.FloodgateVServerAuthListener
import icu.h2l.login.auth.floodgate.service.FloodgateApiHolder
import icu.h2l.login.auth.floodgate.service.FloodgateAuthService
import icu.h2l.login.auth.floodgate.service.FloodgateSessionHolder

class FloodgateSubModule : HyperSubModule {
    override fun register(api: HyperZoneApi) {
        HyperZoneModuleMessageResources.copyBundledLocales(api.dataDirectory, "auth-floodgate", javaClass.classLoader)
        val config = FloodgateAuthConfigLoader.load(api.dataDirectory)
        val profileTable = ProfileTable(api.databaseManager.tablePrefix)
        val tableManager = FloodgateAuthTableManager(
            databaseManager = api.databaseManager,
            tablePrefix = api.databaseManager.tablePrefix,
            profileTable = profileTable,
        )
        val repository = FloodgateAuthRepository(
            databaseManager = api.databaseManager,
            table = tableManager.floodgateAuthTable,
        )
        val floodgateApiHolder = FloodgateApiHolder()
        val authService = FloodgateAuthService(
            api = api,
            floodgateApiHolder = floodgateApiHolder,
            sessionHolder = FloodgateSessionHolder(),
            repository = repository,
            config = config,
        )
        tableManager.createTable()
        api.proxy.eventManager.register(api, tableManager)
        api.proxy.eventManager.register(api, FloodgateOpenStartAuthListener(authService, floodgateApiHolder))
        api.proxy.eventManager.register(api, FloodgateGameProfileListener(authService, floodgateApiHolder))
        api.proxy.eventManager.register(api, FloodgateVServerAuthListener(authService))
        info {
            "FloodgateSubModule 已加载；已创建 Floodgate 专属凭证绑定表，并在 OpenStartAuth 与初始档案校验阶段补注册 Floodgate 接管监听器；自动去除 Floodgate API 玩家名前缀=${config.stripUsernamePrefix}；Profile 解析透传 Floodgate UUID=${config.passFloodgateUuidToProfileResolve}"
        }
    }
}

