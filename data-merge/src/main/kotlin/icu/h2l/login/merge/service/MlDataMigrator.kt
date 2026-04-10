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

package icu.h2l.login.merge.service

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.login.auth.online.api.db.EntryTable
import icu.h2l.login.merge.config.MergeMlConfig
import org.jetbrains.exposed.sql.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class MlDataMigrator(
    private val dataDirectory: Path,
    private val databaseManager: HyperZoneDatabaseManager,
    private val config: MergeMlConfig
) {
    data class Report(
        var sourceProfiles: Int = 0,
        var sourceUserData: Int = 0,
        var targetProfilesCreated: Int = 0,
        var targetProfilesMatched: Int = 0,
        var targetProfileFailures: Int = 0,
        var targetEntriesCreated: Int = 0,
        var targetEntriesMatched: Int = 0,
        var targetEntryConflicts: Int = 0,
        var targetEntryFailures: Int = 0,
        var missingProfileReference: Int = 0
    )

    fun migrate(): Report {
        val report = Report()
        val mergeDirectory = dataDirectory.resolve("merge")
        Files.createDirectories(mergeDirectory)
        val logPath = mergeDirectory.resolve("merge-ml.log")
        val sourceReader = MlSourceReader(dataDirectory, config)

        MergeMlLogWriter(logPath).use { logger ->
            logger.log("开始执行 ML 迁移")
            logger.log("源库类型: ${config.source.type}")
            logger.log("源表: user=${config.tables.userDataTable}, profile=${config.tables.inGameProfileTable}")

            val (sourceProfiles, sourceUsers) = sourceReader.readProfilesAndUserData()
            report.sourceProfiles = sourceProfiles.size
            report.sourceUserData = sourceUsers.size

            logger.log("读取完成: InGameProfileTableV3=${sourceProfiles.size}, UserDataTableV3=${sourceUsers.size}")

            val profileTable = ProfileTable(databaseManager.tablePrefix)
            val profileUuidToId = mutableMapOf<UUID, UUID>()
            val profileIdToName = mutableMapOf<UUID, String>()
            val entryTables = mutableMapOf<String, EntryTable>()

            databaseManager.executeTransaction {
                SchemaUtils.create(profileTable)

                for (sourceProfile in sourceProfiles) {
                    val profileName = resolveProfileName(sourceProfile)
                    val profileId = generateProfileId(profileName)

                    try {
                        val existingByUuid = profileTable.selectAll()
                            .where { profileTable.uuid eq sourceProfile.inGameUuid }
                            .limit(1)
                            .firstOrNull()

                        val resolvedId = if (existingByUuid != null) {
                            report.targetProfilesMatched++
                            existingByUuid[profileTable.id]
                        } else {
                            val existingByName = profileTable.selectAll()
                                .where { profileTable.name eq profileName }
                                .limit(1)
                                .firstOrNull()

                            if (existingByName != null) {
                                report.targetProfilesMatched++
                                existingByName[profileTable.id]
                            } else {
                                val existingById = profileTable.selectAll()
                                    .where { profileTable.id eq profileId }
                                    .limit(1)
                                    .firstOrNull()

                                if (existingById != null) {
                                    report.targetProfilesMatched++
                                    profileTable.update({ profileTable.id eq profileId }) {
                                        it[uuid] = sourceProfile.inGameUuid
                                    }
                                    profileId
                                } else {
                                    profileTable.insert {
                                        it[id] = profileId
                                        it[name] = profileName
                                        it[uuid] = sourceProfile.inGameUuid
                                    }
                                    report.targetProfilesCreated++
                                    logger.log("[PROFILE][CREATED] id=$profileId name=$profileName uuid=${sourceProfile.inGameUuid}")
                                    profileId
                                }
                            }
                        }

                        profileUuidToId[sourceProfile.inGameUuid] = resolvedId
                        profileIdToName[resolvedId] = profileName
                    } catch (ex: Exception) {
                        report.targetProfileFailures++
                        logger.log("[PROFILE][FAILED] uuid=${sourceProfile.inGameUuid} name=$profileName reason=${ex.message}")
                    }
                }

                for (sourceUser in sourceUsers) {
                    val profileUuid = sourceUser.inGameProfileUuid
                    if (profileUuid == null) {
                        report.missingProfileReference++
                        logger.log("[ENTRY][SKIP] service=${sourceUser.serviceId} onlineUuid=${sourceUser.onlineUuid} reason=in_game_profile_uuid is null")
                        continue
                    }

                    val profileId = profileUuidToId[profileUuid]
                    if (profileId == null) {
                        report.missingProfileReference++
                        logger.log("[ENTRY][SKIP] service=${sourceUser.serviceId} onlineUuid=${sourceUser.onlineUuid} inGameProfileUuid=$profileUuid reason=profile not found")
                        continue
                    }

                    val entryId = (config.serviceIdMapping[sourceUser.serviceId] ?: "ml_${sourceUser.serviceId}")
                        .lowercase()
                    val entryTable = entryTables.getOrPut(entryId) {
                        EntryTable(entryId, databaseManager.tablePrefix, profileTable).also {
                            SchemaUtils.create(it)
                        }
                    }

                    val onlineName = sourceUser.onlineName?.takeIf { it.isNotBlank() }
                        ?: profileIdToName[profileId]
                        ?: "unknown-${sourceUser.onlineUuid.toString().take(8)}"

                    try {
                        val existing = entryTable.selectAll()
                            .where { (entryTable.name eq onlineName) and (entryTable.uuid eq sourceUser.onlineUuid) }
                            .limit(1)
                            .firstOrNull()

                        if (existing == null) {
                            entryTable.insert {
                                it[name] = onlineName
                                it[uuid] = sourceUser.onlineUuid
                                it[pid] = profileId
                            }
                            report.targetEntriesCreated++
                            logger.log("[ENTRY][CREATED] entry=$entryId name=$onlineName uuid=${sourceUser.onlineUuid} pid=$profileId whitelist=${sourceUser.whitelist}")
                        } else {
                            val existingPid = existing[entryTable.pid]
                            if (existingPid == profileId) {
                                report.targetEntriesMatched++
                                logger.log("[ENTRY][MATCHED] entry=$entryId name=$onlineName uuid=${sourceUser.onlineUuid} pid=$profileId")
                            } else {
                                report.targetEntryConflicts++
                                logger.log("[ENTRY][CONFLICT] entry=$entryId name=$onlineName uuid=${sourceUser.onlineUuid} existedPid=$existingPid incomingPid=$profileId")
                            }
                        }
                    } catch (ex: Exception) {
                        report.targetEntryFailures++
                        logger.log("[ENTRY][FAILED] entry=$entryId name=$onlineName uuid=${sourceUser.onlineUuid} pid=$profileId reason=${ex.message}")
                    }
                }
            }

            logger.log("迁移完成，汇总如下")
            logger.log("sourceProfiles=${report.sourceProfiles}")
            logger.log("sourceUserData=${report.sourceUserData}")
            logger.log("targetProfilesCreated=${report.targetProfilesCreated}")
            logger.log("targetProfilesMatched=${report.targetProfilesMatched}")
            logger.log("targetProfileFailures=${report.targetProfileFailures}")
            logger.log("targetEntriesCreated=${report.targetEntriesCreated}")
            logger.log("targetEntriesMatched=${report.targetEntriesMatched}")
            logger.log("targetEntryConflicts=${report.targetEntryConflicts}")
            logger.log("targetEntryFailures=${report.targetEntryFailures}")
            logger.log("missingProfileReference=${report.missingProfileReference}")
        }

        return report
    }

    private fun resolveProfileName(row: MlInGameProfileRow): String {
        return row.currentUsernameOriginal
            ?.takeIf { it.isNotBlank() }
            ?: row.currentUsernameLowerCase
                ?.takeIf { it.isNotBlank() }
            ?: "unknown-${row.inGameUuid.toString().take(8)}"
    }

    private fun generateProfileId(profileName: String): UUID {
        return UUID.randomUUID()
    }
}
