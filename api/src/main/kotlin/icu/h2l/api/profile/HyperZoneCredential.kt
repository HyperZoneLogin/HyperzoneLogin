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

package icu.h2l.api.profile

import java.util.UUID

/**
 * 认证子模块向登录核心提交的可信凭证。
 *
 * 注意：凭证对象本身不直接携带 profileId 字段；
 * 是否已绑定、如何绑定到 Profile，都由各认证模块自己的实现负责。
 */
interface HyperZoneCredential {
    /**
     * 认证渠道唯一标识，由子模块负责稳定定义。
     */
    val channelId: String

    /**
     * 该渠道内部可识别的凭证标识。
     */
    val credentialId: String

    /**
     * 读取该凭证当前已经绑定到的 Profile。
     *
     * 返回 null 表示该凭证尚未完成绑定。
     */
    fun getBoundProfileId(): UUID?

    /**
     * 在真正写入绑定关系前做一次校验。
     *
     * 返回 null 表示允许绑定；否则返回拒绝原因。
     */
    fun validateBind(profileId: UUID): String? {
        return null
    }

    /**
     * 将该凭证绑定到指定 Profile。
     *
     * 实现应把绑定关系写入模块自己的数据表。
     */
    fun bind(profileId: UUID): Boolean
}

