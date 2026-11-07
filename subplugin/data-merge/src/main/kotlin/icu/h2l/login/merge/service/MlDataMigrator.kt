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
import icu.h2l.api.db.table.InGameProfileTable
import icu.h2l.api.db.table.UserTable
import icu.h2l.login.auth.floodgate.api.db.FloodgateAuthTable
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
        var targetUsersCreated: Int = 0,
        var targetUsersMatched: Int = 0,
        var targetUserFailures: Int = 0,
        var targetEntriesCreated: Int = 0,
        var targetEntriesMatched: Int = 0,
        var targetEntryConflicts: Int = 0,
        var targetEntryFailures: Int = 0,
        var missingProfileReference: Int = 0,
        var floodgateEntriesCreated: Int = 0,
        var floodgateEntriesMatched: Int = 0,
        var floodgateEntryConflicts: Int = 0,
        var floodgateEntryFailures: Int = 0,
        var floodgateInvalidUuid: Int = 0,
    )

    fun migrate(): Report {
        val report = Report()
        val mergeDirectory = dataDirectory.resolve("data-merge")
        Files.createDirectories(mergeDirectory)
        val logPath = mergeDirectory.resolve("merge-ml.log")
        val sourceReader = MlSourceReader(dataDirectory, config)

        MergeMlLogWriter(logPath).use { logger ->
            logger.log("开始执行 ML 迁移")
            logger.log("源库类型: ${config.source.type}")
            logger.log("源表: user=${config.tables.userDataTable}, profile=${config.tables.inGameUserTable}")

            val (sourceProfiles, sourceUsers) = sourceReader.readProfilesAndUserData()
            report.sourceProfiles = sourceProfiles.size
            report.sourceUserData = sourceUsers.size

            logger.log("读取完成: InGameUserTableV3=${sourceProfiles.size}, UserDataTableV3=${sourceUsers.size}")

            val userTable = UserTable(databaseManager.tablePrefix)
            val inGameProfileTable = InGameProfileTable(databaseManager.tablePrefix, userTable)
            val userWriter = MergeUserWriter(userTable, inGameProfileTable)
            val profileUuidToId = mutableMapOf<UUID, UUID>()
            val userUUIDToName = mutableMapOf<UUID, String>()
            val entryTables = mutableMapOf<String, EntryTable>()
            val floodgateAuthTable = FloodgateAuthTable(databaseManager.tablePrefix, userTable)

            databaseManager.executeTransaction {
                SchemaUtils.create(userTable)
                SchemaUtils.create(inGameProfileTable)

                for (sourceProfile in sourceProfiles) {
                    val profileName = resolveProfileName(sourceProfile)

                    try {
                        val existingUserUUID = userWriter.findProfileId(profileName, sourceProfile.inGameUuid)
                        val resolvedUserId = if (existingUserUUID == null) {
                            val createdUserId = userWriter.createProfile(profileName, sourceProfile.inGameUuid)
                            report.targetUsersCreated++
                            logger.log("[PROFILE][CREATED] id=$createdUserId name=$profileName uuid=${sourceProfile.inGameUuid}")
                            createdUserId
                        } else {
                            val existingIdentity = userWriter.findIdentity(existingUserUUID, sourceProfile.inGameUuid)
                            if (existingIdentity == null) {
                                userWriter.createIdentity(existingUserUUID, profileName, sourceProfile.inGameUuid)
                            }
                            report.targetUsersMatched++
                            logger.log("[PROFILE][MATCHED] id=$existingUserUUID name=$profileName uuid=${sourceProfile.inGameUuid}")
                            existingUserUUID
                        }

                        profileUuidToId[sourceProfile.inGameUuid] = resolvedUserId
                        userUUIDToName[resolvedUserId] = profileName
                    } catch (ex: Exception) {
                        report.targetUserFailures++
                        logger.log("[PROFILE][FAILED] uuid=${sourceProfile.inGameUuid} name=$profileName reason=${ex.message}")
                    }
                }

                @Suppress("DEPRECATION")
                SchemaUtils.createMissingTablesAndColumns(floodgateAuthTable, withLogs = false)

                for (sourceUser in sourceUsers) {
                    val profileUuid = sourceUser.inGameProfileUuid
                    if (profileUuid == null) {
                        report.missingProfileReference++
                        logger.log("[ENTRY][SKIP] service=${sourceUser.serviceId} onlineUuid=${sourceUser.onlineUuid} reason=in_game_profile_uuid is null")
                        continue
                    }

                    val userUUID = profileUuidToId[profileUuid]
                    if (userUUID == null) {
                        report.missingProfileReference++
                        logger.log("[ENTRY][SKIP] service=${sourceUser.serviceId} onlineUuid=${sourceUser.onlineUuid} inGameProfileUuid=$profileUuid reason=profile not found")
                        continue
                    }

                    // Floodgate 特殊迁移路径
                    if (sourceUser.serviceId in config.floodgateServiceIds) {
                        val onlineUuid = sourceUser.onlineUuid
                        // isFloodgateId: mostSignificantBits == 0
                        if (onlineUuid.mostSignificantBits != 0L) {
                            report.floodgateInvalidUuid++
                            logger.log("[FLOODGATE][WARN] service=${sourceUser.serviceId} onlineUuid=$onlineUuid pid=$userUUID reason=not_a_floodgate_uuid (mostSignificantBits != 0)")
                            continue
                        }
                        // xuid = leastSignificantBits (Floodgate: new UUID(0, xuid))
                        val xuid = onlineUuid.leastSignificantBits
                        val onlineName = sourceUser.onlineName?.takeIf { it.isNotBlank() }
                            ?: userUUIDToName[userUUID]
                            ?: "unknown-${xuid}"

                        try {
                            val existing = floodgateAuthTable.selectAll()
                                .where { floodgateAuthTable.xuid eq xuid }
                                .limit(1)
                                .firstOrNull()

                            if (existing == null) {
                                floodgateAuthTable.insert { stmt ->
                                    stmt[floodgateAuthTable.name] = onlineName
                                    stmt[floodgateAuthTable.xuid] = xuid
                                    stmt[floodgateAuthTable.userUUID] = userUUID
                                }
                                report.floodgateEntriesCreated++
                                logger.log("[FLOODGATE][CREATED] xuid=$xuid name=$onlineName pid=$userUUID")
                            } else {
                                val existingPid: UUID = existing[floodgateAuthTable.userUUID]
                                if (existingPid == userUUID) {
                                    report.floodgateEntriesMatched++
                                    logger.log("[FLOODGATE][MATCHED] xuid=$xuid name=$onlineName pid=$userUUID")
                                } else {
                                    report.floodgateEntryConflicts++
                                    logger.log("[FLOODGATE][CONFLICT] xuid=$xuid name=$onlineName existedPid=$existingPid incomingPid=$userUUID")
                                }
                            }
                        } catch (ex: Exception) {
                            report.floodgateEntryFailures++
                            logger.log("[FLOODGATE][FAILED] xuid=$xuid name=$onlineName pid=$userUUID reason=${ex.message}")
                        }
                        continue
                    }

                    val entryId = (config.serviceIdMapping[sourceUser.serviceId] ?: "ml_${sourceUser.serviceId}")
                        .lowercase()
                    val entryTable = entryTables.getOrPut(entryId) {
                        EntryTable(entryId, databaseManager.tablePrefix, userTable).also {
                            SchemaUtils.create(it)
                        }
                    }

                    val onlineName = sourceUser.onlineName?.takeIf { it.isNotBlank() }
                        ?: userUUIDToName[userUUID]
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
                                it[pid] = userUUID
                            }
                            report.targetEntriesCreated++
                            logger.log("[ENTRY][CREATED] entry=$entryId name=$onlineName uuid=${sourceUser.onlineUuid} pid=$userUUID whitelist=${sourceUser.whitelist}")
                        } else {
                            val existingPid = existing[entryTable.pid]
                            if (existingPid == userUUID) {
                                report.targetEntriesMatched++
                                logger.log("[ENTRY][MATCHED] entry=$entryId name=$onlineName uuid=${sourceUser.onlineUuid} pid=$userUUID")
                            } else {
                                report.targetEntryConflicts++
                                logger.log("[ENTRY][CONFLICT] entry=$entryId name=$onlineName uuid=${sourceUser.onlineUuid} existedPid=$existingPid incomingPid=$userUUID")
                            }
                        }
                    } catch (ex: Exception) {
                        report.targetEntryFailures++
                        logger.log("[ENTRY][FAILED] entry=$entryId name=$onlineName uuid=${sourceUser.onlineUuid} pid=$userUUID reason=${ex.message}")
                    }
                }
            }

            logger.log("迁移完成，汇总如下")
            logger.log("sourceProfiles=${report.sourceProfiles}")
            logger.log("sourceUserData=${report.sourceUserData}")
            logger.log("targetUsersCreated=${report.targetUsersCreated}")
            logger.log("targetUsersMatched=${report.targetUsersMatched}")
            logger.log("targetUserFailures=${report.targetUserFailures}")
            logger.log("targetEntriesCreated=${report.targetEntriesCreated}")
            logger.log("targetEntriesMatched=${report.targetEntriesMatched}")
            logger.log("targetEntryConflicts=${report.targetEntryConflicts}")
            logger.log("targetEntryFailures=${report.targetEntryFailures}")
            logger.log("missingProfileReference=${report.missingProfileReference}")
            logger.log("floodgateEntriesCreated=${report.floodgateEntriesCreated}")
            logger.log("floodgateEntriesMatched=${report.floodgateEntriesMatched}")
            logger.log("floodgateEntryConflicts=${report.floodgateEntryConflicts}")
            logger.log("floodgateEntryFailures=${report.floodgateEntryFailures}")
            logger.log("floodgateInvalidUuid=${report.floodgateInvalidUuid}")
        }

        return report
    }

    private fun resolveProfileName(row: MlInGameUserRow): String {
        return row.currentUsernameOriginal
            ?.takeIf { it.isNotBlank() }
            ?: row.currentUsernameLowerCase
                ?.takeIf { it.isNotBlank() }
            ?: "unknown-${row.inGameUuid.toString().take(8)}"
    }
}
