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

package icu.h2l.login.safe.service

import icu.h2l.login.safe.config.SafeConfig

class UsernameValidator(
    private val config: SafeConfig.UsernameConfig
) {
    private val pattern by lazy { Regex(config.pattern) }

    fun validate(username: String): String? {
        if (!config.enable) {
            return null
        }
        if (username.length !in config.minLength..config.maxLength) {
            return "用户名长度不符合要求"
        }
        if (config.denyLeadingOrTrailingWhitespace && username != username.trim()) {
            return "用户名不能包含首尾空白"
        }
        if (!pattern.matches(username)) {
            return "用户名包含不允许的字符"
        }
        return null
    }
}

