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

package icu.h2l.login.profile.skin.net

import icu.h2l.api.net.HttpClients
import icu.h2l.api.net.ProxySettings
import icu.h2l.login.profile.skin.config.ProxyConfig
import java.net.Proxy
import java.net.ProxySelector
import java.net.http.HttpClient

/**
 * profile-skin 出网请求的代理支持。
 *
 * 实际实现位于 api 的 [HttpClients]，这里只负责把模块配置映射为 [ProxySettings]，
 * 让 MineSkin 调用与皮肤原图下载都能走配置的代理，
 * 避免在受限网络下出现 `ConnectException` / `UnresolvedAddressException`。
 */
internal object HttpProxySupport {

    /**
     * 按代理配置构建 HttpClient。
     *
     * @param proxy 代理配置；[ProxyConfig.type] 为 `DIRECT` 时显式直连，
     *   不受 JVM `http.proxyHost` 等系统属性影响
     * @param connectTimeoutMillis 连接超时，单位毫秒
     */
    fun buildHttpClient(proxy: ProxyConfig, connectTimeoutMillis: Long): HttpClient {
        return HttpClients.withProxy(proxy.toSettings(), connectTimeoutMillis)
    }

    /**
     * 将模块配置映射为共享的 [ProxySettings]。
     */
    fun ProxyConfig.toSettings(): ProxySettings {
        return ProxySettings(
            type = type,
            hostname = hostname,
            port = port,
            username = username,
            password = password
        )
    }

    internal fun resolveType(raw: String?): Proxy.Type = HttpClients.resolveType(raw)

    fun proxySelector(proxy: ProxyConfig): ProxySelector = HttpClients.proxySelector(proxy.toSettings())
}
