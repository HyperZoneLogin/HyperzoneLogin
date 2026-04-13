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

package icu.h2l.login.auth.online.config.entry

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class EntryConfig {

    @Comment("此验证服务的 ID(无论大小写，到数据库会变成小写)，用于识别验证服务的唯一标识")
    var id: String = "Example"

    @Comment("验证服务的别称，用于一些指令结果或其他用途的内容显示")
    var name: String = "Unnamed"

    @Comment("Yggdrasil 类型账号验证服务配置（当 serviceType 为 Yggdrasil 时有效）")
    var yggdrasil: YggdrasilAuthConfig = YggdrasilAuthConfig()

    @ConfigSerializable
    class YggdrasilAuthConfig {
        @Comment(
            "Yggdrasil hasJoined 验证 URL"
        )
        var url: String = ""

        @Comment("是否在 canResolveOrCreateProfile / resolveOrCreateProfile 时透传该 Entry 返回的原始 UUID；关闭后改传 null")
        var passYggdrasilUuidToProfileResolve: Boolean = true

        @Comment("验证请求超时时间（毫秒）")
        var timeout: Int = 10000

        @Comment("网络错误时的重试次数")
        var retry: Int = 0

        @Comment("重试请求延迟（毫秒）")
        var retryDelay: Int = 0

        @Comment("代理设置")
        var proxy: ProxyConfig = ProxyConfig()
    }

    @ConfigSerializable
    class ProxyConfig {
        @Comment(
            """设置代理类型
            DIRECT - 直接连接、或没有代理
            HTTP - 表示高级协议(如HTTP或FTP)的代理
            SOCKS - 表示一个SOCKS (V4或V5)代理"""
        )
        var type: String = "DIRECT"

        @Comment("代理服务器地址")
        var hostname: String = "127.0.0.1"

        @Comment("代理服务器端口")
        var port: Int = 1080

        @Comment("代理鉴权用户名，留空则不进行鉴权")
        var username: String = ""

        @Comment("代理鉴权密码")
        var password: String = ""
    }
}