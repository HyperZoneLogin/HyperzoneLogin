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

    // 全局连接频率限制
    @Comment("config.safe.global-rate-limit")
    @JvmField
    val globalRateLimit = RateLimitConfig(maxAttempts = 80, windowSeconds = 10)

    // 同 IP 连接频率限制
    @Comment("config.safe.ip-rate-limit")
    @JvmField
    val ipRateLimit = RateLimitConfig(maxAttempts = 8, windowSeconds = 10)

    // 同 IP 同时在线会话限额（对抗 bot：同一出口 IP 同时在线数上限）
    @Comment("config.safe.ip-limit")
    @JvmField
    val ipLimit = IpLimitConfig()

    // 同 IP 超阈值后的临时冷却
    @Comment("config.safe.ip-cooldown")
    @JvmField
    val ipCooldown = IpCooldownConfig()

    // 自动高峰防护模式
    @Comment("config.safe.strict-mode")
    @JvmField
    val strictMode = StrictModeConfig()

    // 认证失败联动防护
    @Comment("config.safe.auth-failure")
    @JvmField
    val authFailure = AuthFailureConfig()

    // 用户名基础校验
    @Comment("config.safe.username")
    @JvmField
    val username = UsernameConfig()

    @ConfigSerializable
    class RateLimitConfig(
        // 窗口期内最多允许的尝试次数
        @param:Comment("config.safe.rate-limit.max-attempts")
        val maxAttempts: Int = 10,
        // 窗口期长度（秒）
        @param:Comment("config.safe.rate-limit.window-seconds")
        val windowSeconds: Int = 10
    )

    @ConfigSerializable
    class IpLimitConfig {
        // 同 IP 同时在线限制开关
        @Comment("config.safe.ip-limit.enable")
        val enable = true

        // 同一 IP 同时允许的在线会话数上限；0 表示不限
        @Comment("config.safe.ip-limit.max")
        val max = 3
    }

    @ConfigSerializable
    class UsernameConfig {
        // 用户名基础校验
        @Comment("config.safe.username.enable")
        val enable = true

        // 最短长度
        @Comment("config.safe.username.min-length")
        val minLength = 3

        // 最长长度
        @Comment("config.safe.username.max-length")
        val maxLength = 16

        // 用户名不包含首尾空白
        @Comment("config.safe.username.deny-whitespace")
        val denyLeadingOrTrailingWhitespace = true

        // 允许的用户名正则，默认与 Minecraft 传统用户名规则一致
        @Comment("config.safe.username.pattern")
        val pattern = "^[A-Za-z0-9_]+$"
    }

    @ConfigSerializable
    class IpCooldownConfig {
        // 同 IP 临时冷却
        @Comment("config.safe.ip-cooldown.enabled")
        val enabled = true

        // 在统计窗口内触发多少次限流后，开始临时封禁
        @Comment("config.safe.ip-cooldown.trigger-attempts")
        val triggerAttempts = 3

        // 统计窗口长度（秒）
        @Comment("config.safe.ip-cooldown.window-seconds")
        val windowSeconds = 60

        // 触发后的冷却时长（秒）
        @Comment("config.safe.ip-cooldown.cooldown-seconds")
        val cooldownSeconds = 300
    }

    @ConfigSerializable
    class StrictModeConfig {
        // 自动高峰防护模式
        @Comment("config.safe.strict-mode.enabled")
        val enabled = true

        // 全局连接请求在窗口内达到多少次后进入 严格模式
        @Comment("config.safe.strict-mode.trigger-attempts")
        val triggerAttempts = 120

        // 统计窗口（秒）
        @Comment("config.safe.strict-mode.window-seconds")
        val windowSeconds = 15

        // 保持时长（秒）
        @Comment("config.safe.strict-mode.recover-after")
        val recoverAfterSeconds = 90

        // 全局限流
        @Comment("config.safe.strict-mode.global-rate-limit")
        @JvmField
        val globalRateLimit = RateLimitConfig(maxAttempts = 30, windowSeconds = 10)

        // 同 IP 限流
        @Comment("config.safe.strict-mode.ip-rate-limit")
        @JvmField
        val ipRateLimit = RateLimitConfig(maxAttempts = 4, windowSeconds = 10)
    }

    @ConfigSerializable
    class AuthFailureConfig {
        // 统一认证失败联动
        @Comment("config.safe.auth-failure.enabled")
        val enabled = true

        // 同一 IP 在统计窗口内累计多少次认证失败后开始冷却
        @Comment("config.safe.auth-failure.trigger-attempts")
        val triggerAttempts = 4

        // 认证失败统计窗口（秒）
        @Comment("config.safe.auth-failure.window-seconds")
        val windowSeconds = 300

        // 触发后的冷却时长（秒）
        @Comment("config.safe.auth-failure.cooldown-seconds")
        val cooldownSeconds = 600
    }
}
