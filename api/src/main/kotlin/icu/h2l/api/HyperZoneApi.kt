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

package icu.h2l.api

import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.command.HyperChatCommandManagerProvider
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.player.HyperZonePlayerAccessorProvider
import icu.h2l.api.vServer.HyperZoneVServerProvider
import java.nio.file.Path

interface HyperZoneApi :
    HyperChatCommandManagerProvider,
    HyperZonePlayerAccessorProvider,
    HyperZoneVServerProvider {
    val proxy: ProxyServer
    val dataDirectory: Path
    val databaseManager: HyperZoneDatabaseManager
    val isGameProfileReplacementEnabled: Boolean

    fun registerModule(module: HyperSubModule)
}

object HyperZoneApiProvider {
    @Volatile
    private var api: HyperZoneApi? = null

    fun bind(api: HyperZoneApi) {
        this.api = api
    }

    fun get(): HyperZoneApi = api ?: error("HyperZoneLogin API is not available yet")

    fun getOrNull(): HyperZoneApi? = api
}

