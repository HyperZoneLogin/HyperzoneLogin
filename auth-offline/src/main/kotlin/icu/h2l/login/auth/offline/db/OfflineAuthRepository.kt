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

package icu.h2l.login.auth.offline.db

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.log.warn
import icu.h2l.login.auth.offline.api.db.OfflineAuthEntry
import icu.h2l.login.auth.offline.api.db.OfflineAuthTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

class OfflineAuthRepository(
    private val databaseManager: HyperZoneDatabaseManager,
    private val table: OfflineAuthTable
) {
    fun create(name: String, passwordHash: String, hashFormat: String, profileId: UUID, email: String? = null): Boolean {
        val normalizedName = name.lowercase()
        return try {
            databaseManager.executeTransaction {
                table.insert {
                    it[this.name] = normalizedName
                    it[this.passwordHash] = passwordHash
                    it[this.hashFormat] = hashFormat
                    it[this.profileId] = profileId
                    it[this.email] = email?.lowercase()
                }
            }
            true
        } catch (e: Exception) {
            warn { "创建离线认证记录失败: ${e.message}" }
            false
        }
    }

    fun getByName(name: String): OfflineAuthEntry? {
        val normalizedName = name.lowercase()
        return databaseManager.executeTransaction {
            table.selectAll().where { table.name eq normalizedName }
                .limit(1)
                .map(::toEntry)
                .firstOrNull()
        }
    }

    fun getByProfileId(profileId: UUID): OfflineAuthEntry? {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.profileId eq profileId }
                .limit(1)
                .map(::toEntry)
                .firstOrNull()
        }
    }

    fun getByEmail(email: String): OfflineAuthEntry? {
        val normalizedEmail = email.lowercase()
        return databaseManager.executeTransaction {
            table.selectAll().where { table.email eq normalizedEmail }
                .limit(1)
                .map(::toEntry)
                .firstOrNull()
        }
    }

    fun updatePassword(profileId: UUID, passwordHash: String, hashFormat: String): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.profileId eq profileId }) {
                    it[this.passwordHash] = passwordHash
                    it[this.hashFormat] = hashFormat
                    it[this.recoveryCodeHash] = null
                    it[this.recoveryCodeExpireAt] = null
                    it[this.recoveryRequestedAt] = null
                    it[this.recoveryVerifyTries] = 0
                    it[this.resetPasswordVerifiedUntil] = null
                    it[this.loginFailCount] = 0
                    it[this.loginBlockedUntil] = null
                    it[this.sessionIp] = null
                    it[this.sessionIssuedAt] = null
                    it[this.sessionExpiresAt] = null
                }
            } > 0
        } catch (e: Exception) {
            warn { "更新离线认证密码失败: ${e.message}" }
            false
        }
    }

    fun updateEmail(profileId: UUID, email: String?): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.profileId eq profileId }) {
                    it[this.email] = email?.lowercase()
                }
            } > 0
        } catch (e: Exception) {
            warn { "更新离线认证邮箱失败: ${e.message}" }
            false
        }
    }

    fun updateLoginProtection(profileId: UUID, failCount: Int, blockedUntil: Long?): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.profileId eq profileId }) {
                    it[this.loginFailCount] = failCount
                    it[this.loginBlockedUntil] = blockedUntil
                }
            } > 0
        } catch (e: Exception) {
            warn { "更新离线认证登录保护状态失败: ${e.message}" }
            false
        }
    }

    fun resetLoginProtection(profileId: UUID): Boolean {
        return updateLoginProtection(profileId, 0, null)
    }


    fun startRecovery(profileId: UUID, recoveryCodeHash: String, expireAt: Long, requestedAt: Long): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.profileId eq profileId }) {
                    it[this.recoveryCodeHash] = recoveryCodeHash
                    it[this.recoveryCodeExpireAt] = expireAt
                    it[this.recoveryRequestedAt] = requestedAt
                    it[this.recoveryVerifyTries] = 0
                    it[this.resetPasswordVerifiedUntil] = null
                }
            } > 0
        } catch (e: Exception) {
            warn { "写入离线认证恢复码失败: ${e.message}" }
            false
        }
    }

    fun incrementRecoveryVerifyTries(profileId: UUID): Boolean {
        return try {
            databaseManager.executeTransaction {
                val current = table.selectAll().where { table.profileId eq profileId }
                    .limit(1)
                    .map { it[table.recoveryVerifyTries] }
                    .firstOrNull()
                    ?: return@executeTransaction false

                table.update({ table.profileId eq profileId }) {
                    it[this.recoveryVerifyTries] = current + 1
                } > 0
            }
        } catch (e: Exception) {
            warn { "更新离线认证恢复码尝试次数失败: ${e.message}" }
            false
        }
    }

    fun markRecoveryVerified(profileId: UUID, verifiedUntil: Long): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.profileId eq profileId }) {
                    it[this.resetPasswordVerifiedUntil] = verifiedUntil
                    it[this.recoveryCodeHash] = null
                    it[this.recoveryCodeExpireAt] = null
                    it[this.recoveryRequestedAt] = null
                    it[this.recoveryVerifyTries] = 0
                }
            } > 0
        } catch (e: Exception) {
            warn { "标记离线认证恢复码已验证失败: ${e.message}" }
            false
        }
    }

    fun clearRecoveryState(profileId: UUID): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.profileId eq profileId }) {
                    it[this.recoveryCodeHash] = null
                    it[this.recoveryCodeExpireAt] = null
                    it[this.recoveryRequestedAt] = null
                    it[this.recoveryVerifyTries] = 0
                    it[this.resetPasswordVerifiedUntil] = null
                }
            } > 0
        } catch (e: Exception) {
            warn { "清理离线认证恢复状态失败: ${e.message}" }
            false
        }
    }

    fun issueSession(profileId: UUID, sessionIp: String?, issuedAt: Long, expiresAt: Long): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.profileId eq profileId }) {
                    it[this.sessionIp] = sessionIp
                    it[this.sessionIssuedAt] = issuedAt
                    it[this.sessionExpiresAt] = expiresAt
                }
            } > 0
        } catch (e: Exception) {
            warn { "签发离线认证会话失败: ${e.message}" }
            false
        }
    }

    fun clearSession(profileId: UUID): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.profileId eq profileId }) {
                    it[this.sessionIp] = null
                    it[this.sessionIssuedAt] = null
                    it[this.sessionExpiresAt] = null
                }
            } > 0
        } catch (e: Exception) {
            warn { "清理离线认证会话失败: ${e.message}" }
            false
        }
    }

    fun updateTotpSecret(profileId: UUID, totpSecret: String?): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.update({ table.profileId eq profileId }) {
                    it[this.totpSecret] = totpSecret
                    if (totpSecret == null) {
                        it[this.sessionIp] = null
                        it[this.sessionIssuedAt] = null
                        it[this.sessionExpiresAt] = null
                    }
                }
            } > 0
        } catch (e: Exception) {
            warn { "更新离线认证 TOTP 状态失败: ${e.message}" }
            false
        }
    }

    fun deleteByProfileId(profileId: UUID): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.deleteWhere { table.profileId eq profileId }
            } > 0
        } catch (e: Exception) {
            warn { "删除离线认证记录失败: ${e.message}" }
            false
        }
    }

    private fun toEntry(row: org.jetbrains.exposed.sql.ResultRow): OfflineAuthEntry {
        return OfflineAuthEntry(
            id = row[table.id],
            name = row[table.name],
            passwordHash = row[table.passwordHash],
            hashFormat = row[table.hashFormat],
            profileId = row[table.profileId],
            email = row[table.email],
            recoveryCodeHash = row[table.recoveryCodeHash],
            recoveryCodeExpireAt = row[table.recoveryCodeExpireAt],
            recoveryRequestedAt = row[table.recoveryRequestedAt],
            recoveryVerifyTries = row[table.recoveryVerifyTries],
            resetPasswordVerifiedUntil = row[table.resetPasswordVerifiedUntil],
            loginFailCount = row[table.loginFailCount],
            loginBlockedUntil = row[table.loginBlockedUntil],
            sessionIp = row[table.sessionIp],
            sessionIssuedAt = row[table.sessionIssuedAt],
            sessionExpiresAt = row[table.sessionExpiresAt],
            totpSecret = row[table.totpSecret]
        )
    }
}