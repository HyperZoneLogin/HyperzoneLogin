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

package icu.h2l.login.profile.skin.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class ProfileSkinConfig {

    // 优先缓存并已携带签名的材质
    @Comment("config.skin.prefer-upstream-signed")
    var preferUpstreamSignedTextures: Boolean = true

    // 不修复的入口ID
    @Comment("config.skin.trusted-entries")
    var trustedSignedTextureEntries: Set<String> = setOf("mojang")

    // 修复非官方签名
    @Comment("config.skin.restore-unsigned")
    var restoreUnsignedTextures: Boolean = true

    // MineSkin 修复配置
    @Comment("config.skin.mineskin")
    var mineSkin: MineSkinConfig = MineSkinConfig()

    // 出网请求代理设置
    @Comment("config.skin.proxy")
    var proxy: ProxyConfig = ProxyConfig()

    // 模块调试日志
    @Comment("config.skin.debug")
    var debug: Boolean = false
}

/**
 * 出网请求代理设置。
 *
 * 字段语义与 auth-yggd 的 entry 级代理配置保持一致，
 * 供 MineSkin 调用与皮肤原图下载等所有出网请求共用。
 */
@ConfigSerializable
class ProxyConfig {
    // 设置代理类型
    // DIRECT - 直接连接、或没有代理
    // HTTP - 表示高级协议(如HTTP或FTP)的代理
    // SOCKS - 表示一个SOCKS (V4或V5)代理
    @Comment("config.skin.proxy.type")
    var type: String = "DIRECT"

    // 代理服务器地址
    @Comment("config.skin.proxy.hostname")
    var hostname: String = "127.0.0.1"

    // 代理服务器端口
    @Comment("config.skin.proxy.port")
    var port: Int = 1080

    // 代理鉴权用户名，留空则不进行鉴权
    @Comment("config.skin.proxy.username")
    var username: String = ""

    // 代理鉴权密码
    @Comment("config.skin.proxy.password")
    var password: String = ""
}

@ConfigSerializable
class MineSkinConfig {
    // 生成方式：URL 或 UPLOAD
    @Comment("config.skin.mineskin.method")
    var method: String = "URL"

    // 出错时改模式重试
    @Comment("config.skin.mineskin.retry-upload")
    var retryUploadOnUrlReadFailure: Boolean = true

    // URL 模式接口地址
    @Comment("config.skin.mineskin.url-endpoint")
    var urlEndpoint: String = "https://api.mineskin.org/generate/url"

    // 上传模式接口地址
    @Comment("config.skin.mineskin.upload-endpoint")
    var uploadEndpoint: String = "https://api.mineskin.org/generate/upload"

    // 请求超时时间（毫秒）
    @Comment("config.skin.mineskin.timeout")
    var timeoutMillis: Long = 15000

    // HTTP User-Agent
    @Comment("config.skin.mineskin.user-agent")
    var userAgent: String = "HyperZoneLogin/1.0"
}

enum class MineSkinMethod {
    URL,
    UPLOAD;

    companion object {
        fun from(raw: String?): MineSkinMethod {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: URL
        }
    }
}
