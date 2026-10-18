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

package icu.h2l.login.auth.offline.api.db

import icu.h2l.api.db.table.ProfileTable
import org.jetbrains.exposed.sql.Table
import java.util.*

data class OfflineAuthEntry(
    val id: Int,
    val name: String,
    val passwordHash: String,
    val hashFormat: String,
    val profileId: UUID,
    val email: String?,
    val recoveryCodeHash: String?,
    val recoveryCodeExpireAt: Long?,
    val recoveryRequestedAt: Long?,
    val recoveryVerifyTries: Int,
    val resetPasswordVerifiedUntil: Long?,
    val loginFailCount: Int,
    val loginBlockedUntil: Long?,
    val sessionIp: String?,
    val sessionIssuedAt: Long?,
    val sessionExpiresAt: Long?,
    val totpSecret: String?
)

/**
 * 离线认证正式数据表。
 *
 * 重要约束：该表只允许保存“已经完成 Profile 绑定”的正式离线认证记录，
 * 因此 [profileId] 永远不能为空。
 * 任何待绑定阶段的临时注册数据（例如密码哈希）都必须留在临时管理器中，
 * 等绑定成功后再创建正式 entry，绝不能先写入一条 `profileId = null` 的库记录。
 */
class OfflineAuthTable(prefix: String, profileTable: ProfileTable) : Table("${prefix}offline_auth") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 32)
    val passwordHash = varchar("password_hash", 255)
    val hashFormat = varchar("hash_format", 32)
    val profileId = uuid("profile_id").references(profileTable.id)
    val email = varchar("email", 255).nullable()
    val recoveryCodeHash = varchar("recovery_code_hash", 255).nullable()
    val recoveryCodeExpireAt = long("recovery_code_expire_at").nullable()
    val recoveryRequestedAt = long("recovery_requested_at").nullable()
    val recoveryVerifyTries = integer("recovery_verify_tries").default(0)
    val resetPasswordVerifiedUntil = long("reset_password_verified_until").nullable()
    val loginFailCount = integer("login_fail_count").default(0)
    val loginBlockedUntil = long("login_blocked_until").nullable()
    val sessionIp = varchar("session_ip", 64).nullable()
    val sessionIssuedAt = long("session_issued_at").nullable()
    val sessionExpiresAt = long("session_expires_at").nullable()
    val totpSecret = varchar("totp_secret", 64).nullable()

    init {
        uniqueIndex("${tableName}_name", name)
        uniqueIndex("${tableName}_profile_id", profileId)
        uniqueIndex("${tableName}_email", email)
    }

    override val primaryKey = PrimaryKey(id)
}