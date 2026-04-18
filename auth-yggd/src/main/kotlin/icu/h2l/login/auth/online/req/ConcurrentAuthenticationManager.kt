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

import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.log.info
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 并发验证管理器
 * 支持向多个Entry并发发送请求，任意一个成功即返回
 */
class ConcurrentAuthenticationManager(
    private val authRequests: List<AuthenticationRequestEntry>,
    private val globalTimeout: Duration = Duration.ofSeconds(30)
) {
    
    /**
     * 执行并发验证
     * @param username 玩家用户名
     * @param serverId 服务器ID
     * @param playerIp 玩家IP（可选）
     * @return 验证结果
     */
    suspend fun authenticate(
        username: String,
        serverId: String,
        playerIp: String? = null
    ): AuthenticationResult = coroutineScope {
        if (authRequests.isEmpty()) {
            return@coroutineScope AuthenticationResult.Failure(
                reason = "未配置可用的认证条目"
            )
        }

        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "开始并发认证，玩家: $username，条目数量: ${authRequests.size}" }

        // 用于标记是否已经有成功的结果
        val completed = AtomicBoolean(false)
        val mutex = Mutex()
        var successResult: AuthenticationResult.Success? = null
        val failures = mutableListOf<AuthenticationResult.Failure>()

        try {
            // 设置全局超时
            withTimeout(globalTimeout.toMillis()) {
                // 为每个验证请求创建一个协程
                val jobs = authRequests.map { authRequestEntry ->
                    async(Dispatchers.IO) {
                        val entryId = authRequestEntry.entryId
                        if (completed.get()) {
                            debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "条目 $entryId: 跳过执行 - 其他条目已先成功" }
                            return@async null
                        }

                        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "条目 $entryId: 开始认证请求" }
                        val result = try {
                            authRequestEntry.request.authenticate(username, serverId, playerIp)
                        } catch (e: Exception) {
                            debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "条目 $entryId: 发生异常 - ${e.message}" }
                            AuthenticationResult.Failure(
                                reason = "发生异常: ${e.message}",
                                statusCode = null
                            )
                        }

                        // 处理结果
                        when (result) {
                            is AuthenticationResult.Success -> {
                                // 检查是否是第一个成功的
                                if (completed.compareAndSet(false, true)) {
                                    mutex.withLock {
                                        successResult = result.copy(entryId = entryId)
                                    }
                                    info { "条目 $entryId: 玩家 $username 认证成功" }
                                    result
                                } else {
                                    debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "条目 $entryId: 当前请求成功，但其他条目更快返回" }
                                    null
                                }
                            }
                            is AuthenticationResult.Failure -> {
                                debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "条目 $entryId: 认证失败 - ${result.reason}" }
                                mutex.withLock {
                                    failures.add(result)
                                }
                                null
                            }
                            is AuthenticationResult.Timeout -> {
                                debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "条目 $entryId: 认证超时" }
                                null
                            }
                        }
                    }
                }

                // 等待所有任务完成或第一个成功
                while (jobs.any { !it.isCompleted } && !completed.get()) {
                    delay(10) // 短暂延迟避免忙等待
                }

                // 如果有成功结果，取消其他所有任务
                if (completed.get()) {
                    jobs.forEach { job ->
                        if (!job.isCompleted) {
                            job.cancel()
                        }
                    }
                }

                // 等待所有任务完成清理
                jobs.forEach { it.cancelAndJoin() }
            }
        } catch (e: TimeoutCancellationException) {
            info { "玩家 $username 认证超时，耗时 ${globalTimeout.toMillis()}ms" }
            return@coroutineScope AuthenticationResult.Timeout(
                attemptedServers = authRequests.map { it.entryId }
            )
        }

        // 返回结果
        successResult?.let {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "返回玩家 $username 的认证成功结果" }
            return@coroutineScope it
        }

        // 如果没有成功结果，返回综合的失败信息
        info { "玩家 $username 的所有认证条目均失败" }
        return@coroutineScope AuthenticationResult.Failure(
            reason = "${authRequests.size} 个认证条目全部失败。" +
                    "失败详情: ${failures.joinToString("; ") { "${it.statusCode ?: "无"}: ${it.reason}" }}"
        )
    }
}

/**
 * 构建器类，用于创建 ConcurrentAuthenticationManager
 */
class ConcurrentAuthenticationManagerBuilder {
    private val authRequests = mutableListOf<AuthenticationRequestEntry>()
    private var globalTimeout: Duration = Duration.ofSeconds(30)

    fun addAuthRequest(entry: AuthenticationRequestEntry): ConcurrentAuthenticationManagerBuilder {
        authRequests.add(entry)
        return this
    }

    fun addAuthRequests(requests: List<AuthenticationRequestEntry>): ConcurrentAuthenticationManagerBuilder {
        authRequests.addAll(requests)
        return this
    }

    fun setGlobalTimeout(timeout: Duration): ConcurrentAuthenticationManagerBuilder {
        globalTimeout = timeout
        return this
    }

    fun build(): ConcurrentAuthenticationManager {
        return ConcurrentAuthenticationManager(authRequests, globalTimeout)
    }
}
