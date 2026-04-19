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

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.log.info
import icu.h2l.api.message.HyperZoneModuleMessageResources
import icu.h2l.api.log.warn
import icu.h2l.api.module.HyperSubModule
import icu.h2l.login.merge.command.MergeCommand
import icu.h2l.login.merge.config.MergeAmConfig
import icu.h2l.login.merge.config.MergeMlConfig
import icu.h2l.login.merge.service.AmDataMigrator
import icu.h2l.login.merge.service.MlDataMigrator
import icu.h2l.api.util.ConfigLoader
import org.spongepowered.configurate.ConfigurationOptions
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import org.spongepowered.configurate.kotlin.dataClassFieldDiscoverer
import org.spongepowered.configurate.objectmapping.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

class MergeSubModule : HyperSubModule {
    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy
        val dataDirectory = api.dataDirectory
        val databaseManager: HyperZoneDatabaseManager = api.databaseManager
        HyperZoneModuleMessageResources.copyBundledLocales(dataDirectory, "data-merge", javaClass.classLoader)
        val mergeMlConfig = loadMergeMlConfig(dataDirectory)
        val mergeAmConfig = loadMergeAmConfig(dataDirectory)
        val mlMigrator = MlDataMigrator(dataDirectory, databaseManager, mergeMlConfig)
        val amMigrator = AmDataMigrator(dataDirectory, databaseManager, mergeAmConfig)

        val mergeCommand = MergeCommand(
            runMlMigration = {
                val report = mlMigrator.migrate()
                "profiles(created=${report.targetProfilesCreated}, matched=${report.targetProfilesMatched}, failed=${report.targetProfileFailures}), " +
                    "entries(created=${report.targetEntriesCreated}, matched=${report.targetEntriesMatched}, conflicts=${report.targetEntryConflicts}, failed=${report.targetEntryFailures}, missingProfile=${report.missingProfileReference})"
            },
            runAmMigration = {
                val report = amMigrator.migrate()
                "profiles(created=${report.targetProfilesCreated}, matched=${report.targetProfilesMatched}, failed=${report.targetProfileFailures}), " +
                    "offlineAuth(created=${report.targetOfflineAuthCreated}, matched=${report.targetOfflineAuthMatched}, updated=${report.targetOfflineAuthUpdated}, conflicts=${report.targetOfflineAuthConflicts}, failed=${report.targetOfflineAuthFailures}, invalidPassword=${report.invalidPasswordFormat})"
            }
        ).createCommand()
        val mergeCommandMeta = proxy.commandManager.metaBuilder(mergeCommand).build()
        proxy.commandManager.register(mergeCommandMeta, mergeCommand)

        info { "MergeSubModule 已加载，命令 /hzl-merge ml 和 /hzl-merge am 可用" }
    }

    private fun loadMergeMlConfig(dataDirectory: Path): MergeMlConfig {
        val mergeDirectory = dataDirectory.resolve("merge")
        Files.createDirectories(mergeDirectory)

        return ConfigLoader.loadConfig(
            dataDirectory = mergeDirectory,
            fileName = "merge-ml.conf",
            header = "HyperZoneLogin ML Merge Configuration\n",
            defaultProvider = { MergeMlConfig() },
            postLoadHook = { _, loaded, firstCreation ->
                if (firstCreation) {
                    warn { "首次创建 merge-ml.conf，请按需修改后再执行 /hzl-merge ml" }
                }
                loaded
            }
        )
    }

    private fun loadMergeAmConfig(dataDirectory: Path): MergeAmConfig {
        val mergeDirectory = dataDirectory.resolve("merge")
        Files.createDirectories(mergeDirectory)

        return ConfigLoader.loadConfig(
            dataDirectory = mergeDirectory,
            fileName = "merge-am.conf",
            header = "HyperZoneLogin AUTHME Merge Configuration\n",
            defaultProvider = { MergeAmConfig() },
            postLoadHook = { _, loaded, firstCreation ->
                if (firstCreation) {
                    warn { "首次创建 merge-am.conf，请按需修改后再执行 /hzl-merge am" }
                }
                loaded
            }
        )
    }
}
