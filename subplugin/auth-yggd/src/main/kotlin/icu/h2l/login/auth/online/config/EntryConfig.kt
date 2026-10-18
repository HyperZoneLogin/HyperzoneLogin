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

package icu.h2l.login.auth.online.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class EntryConfig {

    // 入口ID（不区分大小写），用于内部识别
    @Comment("config.entry.id")
    var id: String = "Example"

    // 别称，用于内容显示
    @Comment("config.entry.name")
    var name: String = "Unnamed"

    // 验证服务配置
    @Comment("config.entry.yggdrasil")
    var yggdrasil: YggdrasilAuthConfig = YggdrasilAuthConfig()

    @ConfigSerializable
    class YggdrasilAuthConfig {
        // hasJoined 验证 URL
        @Comment("config.entry.yggdrasil.url")
        var url: String = ""

        // UUID透传
        @Comment("config.entry.yggdrasil.pass-uuid")
        var passYggdrasilUuidToProfileResolve: Boolean = true

        // 验证请求超时时间（毫秒）
        @Comment("config.entry.yggdrasil.timeout")
        var timeout: Int = 10000

        // 重试次数
        @Comment("config.entry.yggdrasil.retry")
        var retry: Int = 0

        // 重试请求延迟（毫秒）
        @Comment("config.entry.yggdrasil.retry-delay")
        var retryDelay: Int = 0

        // 代理设置
        @Comment("config.entry.yggdrasil.proxy")
        var proxy: ProxyConfig = ProxyConfig()
    }

    @ConfigSerializable
    class ProxyConfig {
        // 设置代理类型
        // DIRECT - 直接连接、或没有代理
        // HTTP - 表示高级协议(如HTTP或FTP)的代理
        // SOCKS - 表示一个SOCKS (V4或V5)代理
        @Comment("config.entry.proxy.type")
        var type: String = "DIRECT"

        // 代理服务器地址
        @Comment("config.entry.proxy.hostname")
        var hostname: String = "127.0.0.1"

        // 代理服务器端口
        @Comment("config.entry.proxy.port")
        var port: Int = 1080

        // 代理鉴权用户名，留空则不进行鉴权
        @Comment("config.entry.proxy.username")
        var username: String = ""

        // 代理鉴权密码
        @Comment("config.entry.proxy.password")
        var password: String = ""
    }
}