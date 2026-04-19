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
import java.net.InetSocketAddress

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class VServerConfig(
    @Comment("登录服实现模式：backend 或 outpre。推荐 outpre 模式，若有问题请使用backend模式。")
    val mode: String = "outpre",

    @Comment("认证完成后默认进入的服务器")
    val postAuthDefaultServer: String = "play",

    @Comment("记住认证时收到的服务器跳转请求")
    val rememberRequestedServerDuringAuth: Boolean = true,

    val backend: BackendConfig = BackendConfig(),

    val outpre: OutpreConfig = OutpreConfig()
) {
    @Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
    @ConfigSerializable
    data class BackendConfig(
        @Comment("使用的真实认证等待服 Velocity 服务器名")
        val fallbackAuthServer: String = "lobby",

        @Comment("等待区 UpsertPlayerInfo/TabList 兼容过滤补偿")
        val enablePlayerInfoCompensation: Boolean = true,

        @Comment("档案补偿同步")
        val enableProfileCompensation: Boolean = true,

        @Comment("在线热改 name（风险较低，默认开启）")
        val enableNameHotChange: Boolean = true,

        @Comment("在线热改 UUID（高风险，默认关闭）")
        val enableUuidHotChange: Boolean = false
    )

    @Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
    @ConfigSerializable
    data class OutpreConfig(
        @Comment(
            """认证服的逻辑名，仅用于日志/状态标识；不需要在 Velocity 的 servers 中注册。
如果使用 ViaVersion，你需要在 Velocity 的 servers 中添加注册条目，如 outpre-auth = "127.0.0.1:30066"，但不需要将其配置到 try 队列。"""
        )
        val authLabel: String = "outpre-auth",

        @Comment("认证服的直连 Host")
        val authHost: String = "127.0.0.1",

        @Comment("认证服的直连 Port")
        val authPort: Int = 30066,

        @Comment("转接给认证服时，在连接握手中对后端暴露的 Host；留空时使用 authHost")
        val presentedHost: String = "",

        @Comment("转接给认证服时，在连接握手中对后端暴露的 Port；<=0 时使用 authPort")
        val presentedPort: Int = -1,

        @Comment("转接给认证服时，在连接握手中对后端暴露的玩家源 IP；留空时使用玩家真实 IP")
        val presentedPlayerIp: String = ""
    ) {
        fun resolveOutpreAuthAddress(): InetSocketAddress? {
            val host = authHost.trim()
            if (host.isBlank()) return null
            if (authPort !in 1..65535) return null
            return InetSocketAddress.createUnresolved(host, authPort)
        }

        fun outpreAuthTargetLabel(): String {
            return authLabel.trim().ifBlank { "${authHost.trim()}:$authPort" }
        }

        fun resolveOutprePresentedHost(authAddress: InetSocketAddress): String {
            return presentedHost.trim().ifBlank { authAddress.hostString }
        }

        fun resolveOutprePresentedPort(authAddress: InetSocketAddress): Int {
            return presentedPort.takeIf { it in 1..65535 } ?: authAddress.port
        }

        fun resolveOutprePresentedPlayerIp(clientIp: String): String {
            return presentedPlayerIp.trim().ifBlank { clientIp }
        }
    }
}
