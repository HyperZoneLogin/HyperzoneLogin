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

package icu.h2l.login.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class DebugConfig(
    // 日志调试开关；仅覆盖核心自身的链路，子模块各自维护自己的开关
    @Comment("config.debug.log")
    val log: DebugLogConfig = DebugLogConfig(),

    // 慢测试模式相关配置
    @Comment("config.debug.slow-test")
    val slowTest: SlowTestConfig = SlowTestConfig()
)

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class DebugLogConfig(
    // 通用 debug 日志
    @Comment("config.debug.log.general")
    val general: Boolean = false,

    // OutPre 预登录链路追踪日志
    @Comment("config.debug.log.outpre-trace")
    val outPreTrace: Boolean = false,

    // 后端等待区兼容链路调试日志
    @Comment("config.debug.log.backend-compat")
    val backendCompat: Boolean = false,

    // Netty / GameProfile 重写链路调试日志
    @Comment("config.debug.log.network-rewrite")
    val networkRewrite: Boolean = false,
)

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class SlowTestConfig(
    // 开启后，外部模块直接调用 overVerify 将被忽略，只有等待区 /over 才会真正完成 overVerify
    @Comment("config.debug.slow-test.enabled")
    val enabled: Boolean = false
)
