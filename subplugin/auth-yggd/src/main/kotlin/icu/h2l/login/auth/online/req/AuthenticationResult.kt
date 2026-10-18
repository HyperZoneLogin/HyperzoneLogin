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

import com.velocitypowered.api.util.GameProfile

/**
 * 验证结果的封装类
 */
sealed class AuthenticationResult {
    /**
     * 验证成功
     * @param profile 玩家的游戏档案
        * @param serverUrl 成功验证的Entry URL
     */
    data class Success(
        val profile: GameProfile,
        val serverUrl: String,
        val entryId: String? = null
    ) : AuthenticationResult()

    /**
     * 验证失败
     * @param reason 失败原因
     * @param statusCode HTTP状态码（如果有）
     */
    data class Failure(
        val reason: String,
        val statusCode: Int? = null
    ) : AuthenticationResult()

    /**
     * 验证超时
        * @param attemptedServers 尝试的Entry列表
     */
    data class Timeout(
        val attemptedServers: List<String>
    ) : AuthenticationResult()

    /**
     * 判断是否为成功结果
     */
    fun isSuccess(): Boolean = this is Success

    /**
     * 获取成功结果，如果不是成功则返回null
     */
    fun getSuccessOrNull(): Success? = this as? Success
}
