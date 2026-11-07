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

import java.util.*

/**
 * 绑定码存储抽象。
 *
 * 用于隔离绑定码业务服务与具体数据库实现。
 */
interface BindingCodeStore {
    /**
     * 替换指定 User 的绑定码：删除旧记录后插入新记录。
     */
    fun replace(code: String, userUUID: UUID, createdAt: Long): Boolean

    fun findCode(userUUID: UUID): String?

    fun findProfileId(code: String): UUID?

    fun consume(code: String): Boolean
}

