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

package icu.h2l.login.merge

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.HyperZoneApiProvider
import icu.h2l.api.dependency.HyperDependencyManifest
import icu.h2l.api.dependency.HyperDependencyManager
import icu.h2l.api.dependency.HyperDependencyProgressListener
import icu.h2l.api.dependency.HyperDependencyRepository
import icu.h2l.api.dependency.VelocityHyperDependencyClassPathAppender
import java.nio.file.Path

@Plugin(id = "hzl-data-merge", name = "HyperZoneLogin - Data Merge")
class DataMergePlugin @Inject constructor(
    private val server: ProxyServer,
    @param:DataDirectory private val dataDirectory: Path
) {
    private val logger = java.util.logging.Logger.getLogger("hzl-data-merge")
    @Subscribe
    fun onEnable(@Suppress("UNUSED_PARAMETER") e: ProxyInitializeEvent) {
        val mainPluginPresent = server.pluginManager.getPlugin("hyperzonelogin").isPresent
        if (mainPluginPresent) {
            try {
                val api = HyperZoneApiProvider.get()
                val cacheDirectory = HyperZoneApiProvider.getOrNull()?.dataDirectory?.resolve("libs") ?: dataDirectory.resolve("libs")
                HyperDependencyManager(
                    cacheDirectory,
                    VelocityHyperDependencyClassPathAppender(server, this),
                    HyperDependencyRepository.DEFAULT_REPOSITORIES,
                    object : HyperDependencyProgressListener {
                        override fun onDownloadStart(
                            dependency: icu.h2l.api.dependency.HyperDependency,
                            repository: HyperDependencyRepository,
                            targetPath: Path
                        ) {
                            logger.info("正在下载运行库: ${dependency.id()} <- ${repository.baseUrl}")
                        }

                        override fun onDownloadSuccess(
                            dependency: icu.h2l.api.dependency.HyperDependency,
                            repository: HyperDependencyRepository,
                            targetPath: Path
                        ) {
                            logger.info("运行库下载完成: ${dependency.id()}")
                        }

                        override fun onDownloadFailure(
                            dependency: icu.h2l.api.dependency.HyperDependency,
                            repository: HyperDependencyRepository,
                            exception: Exception
                        ) {
                            logger.warning("运行库下载失败: ${dependency.id()} <- ${repository.baseUrl} (${exception.message})")
                        }
                    }
                ).loadDependencies(HyperDependencyManifest.readFrom(this::class.java.classLoader))
                api.registerModule(MergeSubModule())
            } catch (t: Throwable) {
                logger.warning("Failed to register MergeSubModule: ${t.message}")
            }
        } else {
            logger.warning("HyperZoneLogin main plugin not found; MergeSubModule will wait until available.")
        }
    }
}


