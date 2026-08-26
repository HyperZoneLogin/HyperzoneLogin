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

package icu.h2l.login.auth.offline.listener

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.event.auth.LoginHandleRequestEvent
import icu.h2l.api.event.auth.LoginHandleResult
import icu.h2l.login.auth.offline.service.OfflineAuthService
import java.util.concurrent.CompletableFuture

class OfflineSessionAuthListener(
    private val authService: OfflineAuthService
) {

    @Subscribe
    fun onLoginHandleRequest(event: LoginHandleRequestEvent) {
        if (event.proxyPlayer.isOnlineMode) {
            return
        }
        if (event.hyperZonePlayer.hasAttachedProfile()) {
            return
        }

        event.claim("auth-offline:auto-session") { context ->
            val result = authService.tryAutoLoginPrepare(context.proxyPlayer)
            if (result == null) {
                return@claim CompletableFuture.completedFuture(
                    LoginHandleResult.failed("offline session unavailable")
                )
            }
            result.message?.let(context.session::sendMessage)
            if (result.passed) {
                CompletableFuture.completedFuture(
                    LoginHandleResult.success(
                        credential = result.credential,
                        profileIdHint = result.credential?.getBoundProfileId()
                    )
                )
            } else {
                CompletableFuture.completedFuture(LoginHandleResult.failed("offline session rejected"))
            }
        }
    }
}
