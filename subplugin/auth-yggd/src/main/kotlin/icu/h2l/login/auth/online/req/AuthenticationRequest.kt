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

package icu.h2l.login.auth.online.req

import java.time.Duration

/**
 * 验证Entry配置
 */
data class AuthServerConfig(
    val url: String,
    val name: String,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(10)
)

/**
 * 验证请求接口
 */
interface AuthenticationRequest {
    /**
     * 执行验证请求
     * @param username 玩家用户名
     * @param serverId 服务器ID（由shared secret和公钥生成）
     * @param playerIp 玩家IP地址（可选）
     * @return 验证结果
     */
    suspend fun authenticate(
        username: String,
        serverId: String,
        playerIp: String? = null
    ): AuthenticationResult
}

/**
 * 带有Entry ID的验证请求
 */
data class AuthenticationRequestEntry(
    val entryId: String,
    val request: AuthenticationRequest
)
