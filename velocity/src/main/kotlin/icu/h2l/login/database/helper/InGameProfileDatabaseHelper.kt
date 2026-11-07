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

package icu.h2l.login.database.helper

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.InGameProfile
import icu.h2l.api.db.table.InGameProfileTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * InGameProfile 表的记录级数据访问层。
 *
 * 只负责单表读写：
 * - 查询；
 * - 插入；
 * - 删除。
 *
 * 不提供 InGameProfile 的通用更新：`name` / `uuid` 一旦写入即视为不可变，
 * 登录流程按 UUID 命中后直接复用已有记录。渠道来源不属于本表。
 *
 * 不在这里做跨账号唯一性策略判断，也不提供隐式 upsert；
 * 冲突检查、活动身份选择与 create/reuse 分支由 [icu.h2l.login.profile.VelocityHyperZoneUserService] 显式完成。
 */
class InGameProfileDatabaseHelper(
    private val manager: HyperZoneDatabaseManager,
    private val table: InGameProfileTable,
) {
    fun getById(id: UUID): InGameProfile? {
        return manager.executeTransaction {
            table.selectAll().where { table.id eq id }
                .limit(1)
                .map(::toRecord)
                .firstOrNull()
        }
    }

    fun listByProfileId(userUUID: UUID): List<InGameProfile> {
        return manager.executeTransaction {
            table.selectAll().where { table.userUUID eq userUUID }
                .map(::toRecord)
                .sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.uuid }))
        }
    }

    fun listByUuid(uuid: UUID): List<InGameProfile> {
        return manager.executeTransaction {
            table.selectAll().where { table.uuid eq uuid }
                .map(::toRecord)
        }
    }

    fun listByNameLower(nameLower: String): List<InGameProfile> {
        return manager.executeTransaction {
            table.selectAll().where { table.nameLower eq nameLower }
                .map(::toRecord)
        }
    }

    fun findByUuid(uuid: UUID): InGameProfile? {
        return manager.executeTransaction {
            table.selectAll().where { table.uuid eq uuid }
                .limit(1)
                .map(::toRecord)
                .firstOrNull()
        }
    }

    fun insert(
        userUUID: UUID,
        name: String,
        uuid: UUID,
    ): InGameProfile {
        val id = UUID.randomUUID()
        val normalizedName = name.trim()
        val normalizedLower = normalizedName.lowercase(Locale.ROOT)

        manager.executeTransaction {
            table.insert {
                it[this.id] = id
                it[this.userUUID] = userUUID
                it[this.name] = normalizedName
                it[nameLower] = normalizedLower
                it[this.uuid] = uuid
            }
        }

        return InGameProfile(
            id = id,
            userUUID = userUUID,
            name = normalizedName,
            uuid = uuid,
        )
    }

    /**
     * 将指定 User 名下的全部 InGameProfile 迁移到另一个 User。
     *
     * 只更新 `user_uuid`，不修改 `name` / `uuid`。UUID 在整张表中全局唯一，
     * 因此迁移不会产生行冲突。
     *
     * @return 迁移的记录数
     */
    fun reassignUser(sourceUserUUID: UUID, targetUserUUID: UUID): Int {
        return manager.executeTransaction {
            table.update({ table.userUUID eq sourceUserUUID }) {
                it[userUUID] = targetUserUUID
            }
        }
    }

    fun delete(id: UUID): Boolean {
        return manager.executeTransaction {
            table.deleteWhere { table.id eq id } > 0
        }
    }

    private fun toRecord(row: ResultRow): InGameProfile {
        return InGameProfile(
            id = row[table.id],
            userUUID = row[table.userUUID],
            name = row[table.name],
            uuid = row[table.uuid],
        )
    }
}
