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

package icu.h2l.login.safe.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class SafeConfig {
    @Comment("是否启用入口层防护")
    val enable = true

    @Comment("全局连接频率限制")
    @JvmField
    val globalRateLimit = RateLimitConfig(maxAttempts = 80, windowSeconds = 10)

    @Comment("同 IP 连接频率限制")
    @JvmField
    val ipRateLimit = RateLimitConfig(maxAttempts = 8, windowSeconds = 10)

    @Comment("用户名基础校验")
    @JvmField
    val username = UsernameConfig()

    @ConfigSerializable
    class RateLimitConfig(
        @param:Comment("窗口期内最多允许的尝试次数")
        val maxAttempts: Int = 10,
        @param:Comment("窗口期长度（秒）")
        val windowSeconds: Int = 10
    )

    @ConfigSerializable
    class UsernameConfig {
        @Comment("是否启用用户名基础校验")
        val enable = true

        @Comment("最短用户名长度")
        val minLength = 3

        @Comment("最长用户名长度")
        val maxLength = 16

        @Comment("是否要求用户名不能包含首尾空白")
        val denyLeadingOrTrailingWhitespace = true

        @Comment("允许的用户名正则，默认与 Minecraft 传统用户名规则一致")
        val pattern = "^[A-Za-z0-9_]+$"
    }
}


