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

package icu.h2l.login.auth.online.req

import com.google.common.net.UrlEscapers
import com.google.gson.Gson
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Mojang风格的Entry验证请求实现
 */
class MojangStyleAuthRequest(
    private val config: AuthServerConfig,
    private val httpClient: HttpClient,
    private val gson: Gson,
    private val userAgent: String = "HyperZoneLogin/1.0",
    private val preventProxy: Boolean = false
) : AuthenticationRequest {

    override suspend fun authenticate(
        username: String,
        serverId: String,
        playerIp: String?
    ): AuthenticationResult = withContext(Dispatchers.IO) {
        try {
            val url = buildAuthUrl(username, serverId, playerIp)
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilAuth] 即将发起在线认证请求: entry=${config.name}, url=$url" }
            val request = buildHttpRequest(url)
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            handleResponse(response, config.url)
        } catch (e: Exception) {
            AuthenticationResult.Failure(
                reason = "请求失败: ${e.message}",
                statusCode = null
            )
        }
    }

    /**
     * 构建验证URL
     * 使用 EntryConfig.YggdrasilAuthConfig 中的 URL 模板替换占位符
     */
    private fun buildAuthUrl(username: String, serverId: String, playerIp: String?): String {
        val escapedUsername = UrlEscapers.urlFormParameterEscaper().escape(username)
        val escapedServerId = UrlEscapers.urlFormParameterEscaper().escape(serverId)
        
        // 替换模板中的占位符
        var url = config.url
            .replace("{username}", escapedUsername)
            .replace("{serverId}", escapedServerId)
        
        // 处理IP占位符：如果有IP则替换为&ip=xxx，否则替换为空字符串
        val ipParam = if (playerIp != null && preventProxy) {
            val escapedIp = UrlEscapers.urlFormParameterEscaper().escape(playerIp)
            "&ip=$escapedIp"
        } else {
            ""
        }
        url = url.replace("{ip}", ipParam)
        
        return url
    }

    /**
     * 构建HTTP请求
     */
    private fun buildHttpRequest(url: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(config.readTimeout)
            .header("User-Agent", userAgent)
            .GET()
            .build()
    }

    /**
     * 处理HTTP响应
     */
    private fun handleResponse(
        response: HttpResponse<String>,
        serverUrl: String
    ): AuthenticationResult {
        return when (response.statusCode()) {
            200 -> {
                val body=response.body()
                try {
                    val profile = gson.fromJson(body, GameProfile::class.java)
                    AuthenticationResult.Success(profile, serverUrl)
                } catch (e: Exception) {
                    AuthenticationResult.Failure(
                        reason = "响应解析失败: ${e.message}，body:${body}",
                        statusCode = 200
                    )
                }
            }
            204 -> {
                // 离线模式用户尝试登录在线模式代理
                AuthenticationResult.Failure(
                    reason = "离线模式玩家尝试加入在线模式服务器",
                    statusCode = 204
                )
            }
            else -> {
                AuthenticationResult.Failure(
                    reason = "认证服务器返回了预期之外的状态码",
                    statusCode = response.statusCode()
                )
            }
        }
    }
}
