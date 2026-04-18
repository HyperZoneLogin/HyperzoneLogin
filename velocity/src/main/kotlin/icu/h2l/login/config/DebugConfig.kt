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

import icu.h2l.api.log.HyperZoneDebugType
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class DebugConfig(
    @Comment("日志调试开关；建议统一放在此分支下配置")
    val log: DebugLogConfig = DebugLogConfig(),


    @Comment("慢测试模式相关配置")
    val slowTest: SlowTestConfig = SlowTestConfig()
) {
    fun isEnabled(type: HyperZoneDebugType): Boolean {
        return when (type) {
            HyperZoneDebugType.GENERAL -> log.general
            HyperZoneDebugType.OUTPRE_TRACE -> log.outPreTrace
            HyperZoneDebugType.PROFILE_SKIN -> log.profileSkin
            HyperZoneDebugType.BACKEND_COMPAT -> log.backendCompat
            HyperZoneDebugType.NETWORK_REWRITE -> log.networkRewrite
            HyperZoneDebugType.YGGDRASIL_AUTH -> log.yggdrasilAuth
        }
    }
}

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class DebugLogConfig(
    @Comment("开启通用 debug 日志；仅建议用于未细分类型的临时排障")
    val general: Boolean = false,

    @Comment("开启 Floodgate / OutPre 预登录链路追踪日志")
    val outPreTrace: Boolean = false,

    @Comment("开启 ProfileSkin 相关调试日志")
    val profileSkin: Boolean = false,

    @Comment("开启后端等待区兼容链路调试日志")
    val backendCompat: Boolean = false,

    @Comment("开启 Netty / GameProfile 重写链路调试日志")
    val networkRewrite: Boolean = false,

    @Comment("开启 Yggdrasil 认证链路调试日志")
    val yggdrasilAuth: Boolean = false,
)

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class SlowTestConfig(
    @Comment("开启后，外部模块直接调用 overVerify 将被忽略，只有等待区 /over 才会真正完成 overVerify")
    val enabled: Boolean = false
)
