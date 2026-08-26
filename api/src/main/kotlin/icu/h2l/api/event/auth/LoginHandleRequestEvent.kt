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

package icu.h2l.api.event.auth

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 登录编排层触发的处理申请事件。
 *
 * 监听器通过 [claim] 声明自己可以处理当前登录请求，
 * 并返回一个异步结果；登录编排器会并行运行所有 claim，
 * 任一成功即短路，其余任务会收到取消信号。
 */
class LoginHandleRequestEvent(
    val proxyPlayer: Player,
    val hyperZonePlayer: HyperZonePlayer,
    val metadata: Map<String, String> = emptyMap()
) {
    private val claimedHandlers = CopyOnWriteArrayList<LoginClaim>()

    fun claim(moduleId: String, handler: LoginHandleHandler) {
        val normalized = moduleId.trim()
        require(normalized.isNotEmpty()) { "moduleId must not be blank" }
        claimedHandlers += LoginClaim(normalized, handler)
    }

    fun claims(): List<LoginClaim> = claimedHandlers.toList()
}

fun interface LoginHandleHandler {
    fun handle(context: LoginHandleContext): CompletableFuture<LoginHandleResult>
}

data class LoginHandleContext(
    val proxyPlayer: Player,
    val session: LoginHandleSession,
    val metadata: Map<String, String>,
    val cancelSignal: CompletableFuture<Void>
)

interface LoginHandleSession {
    fun originalName(): String
    fun hasAttachedProfile(): Boolean
    fun sendMessage(message: Component)
}

data class LoginClaim(
    val moduleId: String,
    val handler: LoginHandleHandler
)

data class LoginHandleResult(
    val success: Boolean,
    val reason: String? = null,
    val credential: HyperZoneCredential? = null,
    val profileIdHint: UUID? = null,
) {
    companion object {
        @JvmStatic
        fun success(
            credential: HyperZoneCredential? = null,
            profileIdHint: UUID? = null
        ): LoginHandleResult =
            LoginHandleResult(success = true, credential = credential, profileIdHint = profileIdHint)

        @JvmStatic
        fun failed(reason: String): LoginHandleResult = LoginHandleResult(success = false, reason = reason)
    }
}

