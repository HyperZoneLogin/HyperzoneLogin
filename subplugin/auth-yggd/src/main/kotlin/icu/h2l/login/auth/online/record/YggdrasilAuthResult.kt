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

package icu.h2l.login.auth.online.record

import com.velocitypowered.api.util.GameProfile

/**
 * Yggdrasil验证结果
 */
sealed class YggdrasilAuthResult {
    /**
     * 验证成功
     */
    data class Success(
        val profile: GameProfile,
        val entryId: String,
        val serverUrl: String
    ) : YggdrasilAuthResult()

    /**
     * 验证失败
     */
    data class Failed(
        val reason: String,
        val statusCode: Int? = null
    ) : YggdrasilAuthResult()

    /**
     * 验证超时
     */
    object Timeout : YggdrasilAuthResult()

    /**
     * 没有配置的Entry
     */
    object NoEntriesConfigured : YggdrasilAuthResult()

    val isSuccess: Boolean
        get() = this is Success
}
