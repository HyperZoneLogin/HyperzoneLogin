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
import icu.h2l.login.auth.offline.api.db.OfflineAuthTable
import icu.h2l.login.merge.config.MergeAmConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class AmDataMigrator(
    private val dataDirectory: Path,
    private val databaseManager: HyperZoneDatabaseManager,
    private val config: MergeAmConfig
) {
    data class Report(
        var sourceRows: Int = 0,
        var targetProfilesCreated: Int = 0,
        var targetProfilesMatched: Int = 0,
        var targetProfileFailures: Int = 0,
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
        val mergeDirectory = dataDirectory.resolve("merge")
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

            val profileTable = ProfileTable(databaseManager.tablePrefix)
            val offlineAuthTable = OfflineAuthTable(databaseManager.tablePrefix, profileTable)

            databaseManager.executeTransaction {
                SchemaUtils.create(profileTable)
                SchemaUtils.create(offlineAuthTable)

                for (source in sourceRows) {
                    val username = source.username.trim()
                    if (username.isBlank()) {
                        report.targetProfileFailures++
                        report.targetOfflineAuthFailures++
                        logger.log("[ROW][FAILED] username=<blank> reason=username is blank")
                        continue
                    }

                    val profileName = resolveProfileName(source)
                    val offlineAuthName = profileName.lowercase()
                    val generatedProfileId = generateProfileId(username)
                    val profileUuid = generateOfflinePlayerUuid(username)

                    val passwordMeta = parsePassword(source.password)
                    if (passwordMeta == null) {
                        report.invalidPasswordFormat++
                        report.targetOfflineAuthFailures++
                        logger.log("[AUTH][FAILED] username=$username realname=$profileName reason=unsupported password format")
                        continue
                    }

                    val resolvedProfileId = try {
                        val existingByUuid = profileTable.selectAll()
                            .where { profileTable.uuid eq profileUuid }
                            .limit(1)
                            .firstOrNull()

                        if (existingByUuid != null) {
                            report.targetProfilesMatched++
                            val pid = existingByUuid[profileTable.id]
                            if (existingByUuid[profileTable.name] != profileName) {
                                profileTable.update({ profileTable.id eq pid }) {
                                    it[name] = profileName
                                }
                            }
                            pid
                        } else {
                            val existingByName = profileTable.selectAll()
                                .where { profileTable.name eq profileName }
                                .limit(1)
                                .firstOrNull()

                            if (existingByName != null) {
                                report.targetProfilesMatched++
                                val pid = existingByName[profileTable.id]
                                if (existingByName[profileTable.uuid] != profileUuid) {
                                    profileTable.update({ profileTable.id eq pid }) {
                                        it[uuid] = profileUuid
                                    }
                                }
                                pid
                            } else {
                                val existingById = profileTable.selectAll()
                                    .where { profileTable.id eq generatedProfileId }
                                    .limit(1)
                                    .firstOrNull()

                                if (existingById != null) {
                                    report.targetProfilesMatched++
                                    profileTable.update({ profileTable.id eq generatedProfileId }) {
                                        it[name] = profileName
                                        it[uuid] = profileUuid
                                    }
                                    generatedProfileId
                                } else {
                                    profileTable.insert {
                                        it[id] = generatedProfileId
                                        it[name] = profileName
                                        it[uuid] = profileUuid
                                    }
                                    report.targetProfilesCreated++
                                    logger.log("[PROFILE][CREATED] id=$generatedProfileId name=$profileName uuid=$profileUuid username=$username")
                                    generatedProfileId
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        report.targetProfileFailures++
                        report.targetOfflineAuthFailures++
                        logger.log("[PROFILE][FAILED] username=$username name=$profileName uuid=$profileUuid reason=${ex.message}")
                        continue
                    }

                    try {
                        val existingByProfileId = offlineAuthTable.selectAll()
                            .where { offlineAuthTable.profileId eq resolvedProfileId }
                            .limit(1)
                            .firstOrNull()

                        if (existingByProfileId != null) {
                            val same = existingByProfileId[offlineAuthTable.name] == offlineAuthName &&
                                existingByProfileId[offlineAuthTable.passwordHash] == passwordMeta.passwordHash &&
                                existingByProfileId[offlineAuthTable.hashFormat].equals(passwordMeta.hashFormat, ignoreCase = true)

                            if (same) {
                                report.targetOfflineAuthMatched++
                                logger.log("[AUTH][MATCHED] name=$offlineAuthName profileId=$resolvedProfileId")
                            } else {
                                offlineAuthTable.update({ offlineAuthTable.profileId eq resolvedProfileId }) {
                                    it[name] = offlineAuthName
                                    it[passwordHash] = passwordMeta.passwordHash
                                    it[hashFormat] = passwordMeta.hashFormat
                                }
                                report.targetOfflineAuthUpdated++
                                logger.log("[AUTH][UPDATED] name=$offlineAuthName profileId=$resolvedProfileId")
                            }
                            continue
                        }

                        val existingByName = offlineAuthTable.selectAll()
                            .where { offlineAuthTable.name eq offlineAuthName }
                            .limit(1)
                            .firstOrNull()

                        if (existingByName != null) {
                            val existedPid = existingByName[offlineAuthTable.profileId]
                            if (existedPid == resolvedProfileId) {
                                val same = existingByName[offlineAuthTable.passwordHash] == passwordMeta.passwordHash &&
                                    existingByName[offlineAuthTable.hashFormat].equals(passwordMeta.hashFormat, ignoreCase = true)
                                if (same) {
                                    report.targetOfflineAuthMatched++
                                    logger.log("[AUTH][MATCHED] name=$offlineAuthName profileId=$resolvedProfileId")
                                } else {
                                    offlineAuthTable.update({ offlineAuthTable.profileId eq resolvedProfileId }) {
                                        it[passwordHash] = passwordMeta.passwordHash
                                        it[hashFormat] = passwordMeta.hashFormat
                                    }
                                    report.targetOfflineAuthUpdated++
                                    logger.log("[AUTH][UPDATED] name=$offlineAuthName profileId=$resolvedProfileId")
                                }
                            } else {
                                report.targetOfflineAuthConflicts++
                                logger.log("[AUTH][CONFLICT] name=$offlineAuthName existedPid=$existedPid incomingPid=$resolvedProfileId")
                            }
                            continue
                        }

                        offlineAuthTable.insert {
                            it[name] = offlineAuthName
                            it[passwordHash] = passwordMeta.passwordHash
                            it[hashFormat] = passwordMeta.hashFormat
                            it[profileId] = resolvedProfileId
                        }
                        report.targetOfflineAuthCreated++
                        logger.log("[AUTH][CREATED] name=$offlineAuthName profileId=$resolvedProfileId format=${passwordMeta.hashFormat}")
                    } catch (ex: Exception) {
                        report.targetOfflineAuthFailures++
                        logger.log("[AUTH][FAILED] name=$profileName profileId=$resolvedProfileId reason=${ex.message}")
                    }
                }
            }

            logger.log("迁移完成，汇总如下")
            logger.log("sourceRows=${report.sourceRows}")
            logger.log("targetProfilesCreated=${report.targetProfilesCreated}")
            logger.log("targetProfilesMatched=${report.targetProfilesMatched}")
            logger.log("targetProfileFailures=${report.targetProfileFailures}")
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

    private fun generateProfileId(username: String): UUID {
        return UUID.randomUUID()
    }

    companion object {
        private const val HASH_FORMAT_SHA256 = "sha256"
        private const val HASH_FORMAT_AUTHME = "authme"
        private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
    }
}
