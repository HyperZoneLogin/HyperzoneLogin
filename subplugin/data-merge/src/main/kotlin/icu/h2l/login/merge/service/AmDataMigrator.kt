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
import icu.h2l.login.auth.offline.api.db.OfflineAuthTable
import icu.h2l.login.merge.config.MergeAmConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class AmDataMigrator(
    private val dataDirectory: Path,
    private val databaseManager: HyperZoneDatabaseManager,
    private val config: MergeAmConfig
) {
    data class Report(
        var sourceRows: Int = 0,
        var targetUsersCreated: Int = 0,
        var targetUsersMatched: Int = 0,
        var targetUserFailures: Int = 0,
        var targetOfflineAuthCreated: Int = 0,
        var targetOfflineAuthMatched: Int = 0,
        var targetOfflineAuthUpdated: Int = 0,
        var targetOfflineAuthConflicts: Int = 0,
        var targetOfflineAuthFailures: Int = 0,
        var invalidPasswordFormat: Int = 0
    )

    private data class PasswordMeta(
        val hashFormat: String,
        val passwordHash: String
    )

    fun migrate(): Report {
        val report = Report()
        val mergeDirectory = dataDirectory.resolve("data-merge")
        Files.createDirectories(mergeDirectory)
        val logPath = mergeDirectory.resolve("merge-am.log")
        val sourceReader = AmSourceReader(dataDirectory, config)

        MergeAmLogWriter(logPath).use { logger ->
            logger.log("开始执行 AUTHME 迁移")
            logger.log("源库类型: ${config.source.type}")
            logger.log("源表: authme=${config.tables.authMeTable}")

            val sourceRows = sourceReader.readAuthMeRows()
            report.sourceRows = sourceRows.size
            logger.log("读取完成: authme=${sourceRows.size}")

            val userTable = UserTable(databaseManager.tablePrefix)
            val inGameProfileTable = InGameProfileTable(databaseManager.tablePrefix, userTable)
            val userWriter = MergeUserWriter(userTable, inGameProfileTable)
            val offlineAuthTable = OfflineAuthTable(databaseManager.tablePrefix, userTable)

            databaseManager.executeTransaction {
                SchemaUtils.create(userTable)
                SchemaUtils.create(inGameProfileTable)
                SchemaUtils.create(offlineAuthTable)

                for (source in sourceRows) {
                    val username = source.username.trim()
                    if (username.isBlank()) {
                        report.targetUserFailures++
                        report.targetOfflineAuthFailures++
                        logger.log("[ROW][FAILED] username=<blank> reason=username is blank")
                        continue
                    }

                    val profileName = resolveProfileName(source)
                    val offlineAuthName = profileName.lowercase()
                    val profileUuid = generateOfflinePlayerUuid(username)

                    val passwordMeta = parsePassword(source.password)
                    if (passwordMeta == null) {
                        report.invalidPasswordFormat++
                        report.targetOfflineAuthFailures++
                        logger.log("[AUTH][FAILED] username=$username realname=$profileName reason=unsupported password format")
                        continue
                    }

                    val resolvedUserId = try {
                        val existingUserUUID = userWriter.findProfileId(profileName, profileUuid)
                        if (existingUserUUID == null) {
                            val createdUserId = userWriter.createProfile(profileName, profileUuid)
                            report.targetUsersCreated++
                            logger.log("[PROFILE][CREATED] id=$createdUserId name=$profileName uuid=$profileUuid username=$username")
                            createdUserId
                        } else {
                            val existingIdentity = userWriter.findIdentity(existingUserUUID, profileUuid)
                            if (existingIdentity == null) {
                                userWriter.createIdentity(existingUserUUID, profileName, profileUuid)
                            }
                            report.targetUsersMatched++
                            logger.log("[PROFILE][MATCHED] id=$existingUserUUID name=$profileName uuid=$profileUuid username=$username")
                            existingUserUUID
                        }
                    } catch (ex: Exception) {
                        report.targetUserFailures++
                        report.targetOfflineAuthFailures++
                        logger.log("[PROFILE][FAILED] username=$username name=$profileName uuid=$profileUuid reason=${ex.message}")
                        continue
                    }

                    try {
                        val existingByProfileId = offlineAuthTable.selectAll()
                            .where { offlineAuthTable.userUUID eq resolvedUserId }
                            .limit(1)
                            .firstOrNull()

                        if (existingByProfileId != null) {
                            val same = existingByProfileId[offlineAuthTable.name] == offlineAuthName &&
                                existingByProfileId[offlineAuthTable.passwordHash] == passwordMeta.passwordHash &&
                                existingByProfileId[offlineAuthTable.hashFormat].equals(passwordMeta.hashFormat, ignoreCase = true)

                            if (same) {
                                report.targetOfflineAuthMatched++
                                logger.log("[AUTH][MATCHED] name=$offlineAuthName userUUID=$resolvedUserId")
                            } else {
                                offlineAuthTable.update({ offlineAuthTable.userUUID eq resolvedUserId }) {
                                    it[name] = offlineAuthName
                                    it[passwordHash] = passwordMeta.passwordHash
                                    it[hashFormat] = passwordMeta.hashFormat
                                }
                                report.targetOfflineAuthUpdated++
                                logger.log("[AUTH][UPDATED] name=$offlineAuthName userUUID=$resolvedUserId")
                            }
                            continue
                        }

                        val existingByName = offlineAuthTable.selectAll()
                            .where { offlineAuthTable.name eq offlineAuthName }
                            .limit(1)
                            .firstOrNull()

                        if (existingByName != null) {
                            val existedPid = existingByName[offlineAuthTable.userUUID]
                            if (existedPid == resolvedUserId) {
                                val same = existingByName[offlineAuthTable.passwordHash] == passwordMeta.passwordHash &&
                                    existingByName[offlineAuthTable.hashFormat].equals(passwordMeta.hashFormat, ignoreCase = true)
                                if (same) {
                                    report.targetOfflineAuthMatched++
                                    logger.log("[AUTH][MATCHED] name=$offlineAuthName userUUID=$resolvedUserId")
                                } else {
                                    offlineAuthTable.update({ offlineAuthTable.userUUID eq resolvedUserId }) {
                                        it[passwordHash] = passwordMeta.passwordHash
                                        it[hashFormat] = passwordMeta.hashFormat
                                    }
                                    report.targetOfflineAuthUpdated++
                                    logger.log("[AUTH][UPDATED] name=$offlineAuthName userUUID=$resolvedUserId")
                                }
                            } else {
                                report.targetOfflineAuthConflicts++
                                logger.log("[AUTH][CONFLICT] name=$offlineAuthName existedPid=$existedPid incomingPid=$resolvedUserId")
                            }
                            continue
                        }

                        offlineAuthTable.insert {
                            it[name] = offlineAuthName
                            it[passwordHash] = passwordMeta.passwordHash
                            it[hashFormat] = passwordMeta.hashFormat
                            it[userUUID] = resolvedUserId
                        }
                        report.targetOfflineAuthCreated++
                        logger.log("[AUTH][CREATED] name=$offlineAuthName userUUID=$resolvedUserId format=${passwordMeta.hashFormat}")
                    } catch (ex: Exception) {
                        report.targetOfflineAuthFailures++
                        logger.log("[AUTH][FAILED] name=$profileName userUUID=$resolvedUserId reason=${ex.message}")
                    }
                }
            }

            logger.log("迁移完成，汇总如下")
            logger.log("sourceRows=${report.sourceRows}")
            logger.log("targetUsersCreated=${report.targetUsersCreated}")
            logger.log("targetUsersMatched=${report.targetUsersMatched}")
            logger.log("targetUserFailures=${report.targetUserFailures}")
            logger.log("targetOfflineAuthCreated=${report.targetOfflineAuthCreated}")
            logger.log("targetOfflineAuthMatched=${report.targetOfflineAuthMatched}")
            logger.log("targetOfflineAuthUpdated=${report.targetOfflineAuthUpdated}")
            logger.log("targetOfflineAuthConflicts=${report.targetOfflineAuthConflicts}")
            logger.log("targetOfflineAuthFailures=${report.targetOfflineAuthFailures}")
            logger.log("invalidPasswordFormat=${report.invalidPasswordFormat}")
        }

        return report
    }

    private fun resolveProfileName(source: AuthMeRow): String {
        return source.realName?.takeIf { it.isNotBlank() }
            ?: source.username
    }

    private fun parsePassword(password: String?): PasswordMeta? {
        if (password.isNullOrBlank()) {
            return null
        }

        if (password.startsWith("\$SHA\$", ignoreCase = true)) {
            val parts = password.split("$")
            if (parts.size == 4 && parts[3].isNotBlank()) {
                return PasswordMeta(hashFormat = HASH_FORMAT_AUTHME, passwordHash = password.trim())
            }
            return null
        }

        val normalized = password.trim().lowercase()
        return if (SHA256_REGEX.matches(normalized)) {
            PasswordMeta(hashFormat = HASH_FORMAT_SHA256, passwordHash = normalized)
        } else {
            null
        }
    }

    private fun generateOfflinePlayerUuid(username: String): UUID {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:$username").toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private const val HASH_FORMAT_SHA256 = "sha256"
        private const val HASH_FORMAT_AUTHME = "authme"
        private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
    }
}
