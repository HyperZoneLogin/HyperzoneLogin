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

package icu.h2l.api.net

import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * 各模块出网请求共用的代理设置。
 *
 * 字段语义与各模块配置文件中的 `proxy` 段落保持一致，
 * 便于把配置对象直接映射过来。
 *
 * @property type 代理类型：`DIRECT` 直连、`HTTP` 高级协议代理、`SOCKS` 代理
 * @property hostname 代理服务器地址
 * @property port 代理服务器端口
 * @property username 代理鉴权用户名，留空则不鉴权
 * @property password 代理鉴权密码
 */
data class ProxySettings(
    val type: String = "DIRECT",
    val hostname: String = "127.0.0.1",
    val port: Int = 1080,
    val username: String = "",
    val password: String = ""
)

/**
 * 依据 [ProxySettings] 构建带代理的 [HttpClient]。
 *
 * 两处 JDK 行为差异需要留意：
 * 1. HTTP 代理鉴权通过 [HttpClient.Builder.authenticator] 生效，作用域仅限该客户端；
 * 2. SOCKS5 鉴权由 JDK 内部走 `Authenticator.getDefault()`，
 *    因此 SOCKS + 用户名场景必须安装全局默认鉴权器；本实现只在显式配置了用户名时安装，
 *    并保留原有实例作为兜底，避免影响其它请求。
 */
object HttpClients {

    /**
     * 按代理设置构建 HttpClient。
     *
     * @param settings 代理设置；[ProxySettings.type] 为 `DIRECT` 时显式直连，
     *   不受 JVM `http.proxyHost` 等系统属性影响
     * @param connectTimeoutMillis 连接超时，单位毫秒
     */
    @JvmStatic
    fun withProxy(
        settings: ProxySettings,
        connectTimeoutMillis: Long,
        followRedirects: Boolean = true
    ): HttpClient {
        installSocksAuthenticatorIfNeeded(settings)

        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
            .proxy(proxySelector(settings))

        if (followRedirects) {
            builder.followRedirects(HttpClient.Redirect.NORMAL)
        }

        proxyAuthenticator(settings)?.let(builder::authenticator)
        return builder.build()
    }

    /**
     * 将设置映射为 [ProxySelector]。
     *
     * 无法识别的类型按 `DIRECT` 处理；`DIRECT` 返回恒定 `NO_PROXY`，
     * 以保证行为由配置唯一决定。
     */
    @JvmStatic
    fun proxySelector(settings: ProxySettings): ProxySelector {
        val type = resolveType(settings.type)
        if (type == Proxy.Type.DIRECT) {
            return StaticProxySelector(Proxy.NO_PROXY)
        }
        return StaticProxySelector(Proxy(type, InetSocketAddress(settings.hostname, settings.port)))
    }

    /**
     * 解析代理类型字符串，无法识别时按 `DIRECT` 处理。
     */
    @JvmStatic
    fun resolveType(raw: String?): Proxy.Type {
        return when (raw?.trim()?.uppercase()) {
            "HTTP" -> Proxy.Type.HTTP
            "SOCKS" -> Proxy.Type.SOCKS
            else -> Proxy.Type.DIRECT
        }
    }

    private fun proxyAuthenticator(settings: ProxySettings): Authenticator? {
        if (settings.username.isBlank()) {
            return null
        }
        return ProxyCredentialAuthenticator(settings.username, settings.password, fallback = null)
    }

    /**
     * SOCKS5 鉴权由 JDK 的全局默认鉴权器承担，这里按需安装一次。
     */
    private fun installSocksAuthenticatorIfNeeded(settings: ProxySettings) {
        if (resolveType(settings.type) != Proxy.Type.SOCKS) {
            return
        }
        if (settings.username.isBlank()) {
            return
        }

        val current = runCatching { Authenticator.getDefault() }.getOrNull()
        if (current is ProxyCredentialAuthenticator) {
            return
        }

        runCatching {
            Authenticator.setDefault(
                ProxyCredentialAuthenticator(settings.username, settings.password, fallback = current)
            )
        }
    }
}

/**
 * 恒定返回同一个代理的 [ProxySelector]。
 */
private class StaticProxySelector(private val proxy: Proxy) : ProxySelector() {
    override fun select(uri: URI): MutableList<Proxy> = mutableListOf(proxy)

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        // 连接失败的重试与日志由各请求调用点负责，这里不做额外处理。
    }
}

/**
 * 仅对代理请求回填凭据的鉴权器。
 *
 * HTTP 代理场景由 [HttpClient.Builder.authenticator] 注入；
 * SOCKS5 场景由 [Authenticator.setDefault] 注入，此时 [fallback] 保留原全局鉴权器。
 *
 * 非代理请求交给 [fallback] 处理：`getPasswordAuthentication` 是 protected，
 * 子类无法在其它实例上直接调用，因此改走公开的 `requestPasswordAuthenticationInstance`，
 * 由它携带当前请求上下文回调原鉴权器。
 */
private class ProxyCredentialAuthenticator(
    private val username: String,
    private val password: String,
    private val fallback: Authenticator?
) : Authenticator() {

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val isProxyRequest = requestorType == RequestorType.PROXY
        val isSocksRequest = requestorType == RequestorType.SERVER &&
                requestingProtocol?.startsWith("SOCKS", ignoreCase = true) == true

        if (isProxyRequest || isSocksRequest) {
            return PasswordAuthentication(username, password.toCharArray())
        }

        val delegate = fallback ?: return null
        return delegate.requestPasswordAuthenticationInstance(
            requestingHost,
            requestingSite,
            requestingPort,
            requestingProtocol,
            requestingPrompt,
            requestingScheme,
            requestingURL,
            requestorType
        )
    }
}
