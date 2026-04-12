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

package icu.h2l.login.auth.online

import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import icu.h2l.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component

object YggdrasilMessages {
    private const val NAMESPACE = "auth-yggd"

    fun profileResolveFailed(player: HyperZonePlayer, reason: String): Component {
        return render(player, "profile-resolve-failed", reason, HyperZoneMessagePlaceholder.text("reason", reason))
    }

    fun verifyCompleteFailed(player: HyperZonePlayer, reason: String): Component {
        return render(player, "verify-complete-failed", "认证成功，但 Profile 绑定失败", HyperZoneMessagePlaceholder.text("reason", reason))
    }

    fun authFailed(player: HyperZonePlayer, username: String): Component {
        return render(player, "auth-failed", "玩家 <username> Yggdrasil 验证失败", HyperZoneMessagePlaceholder.text("username", username))
    }

    fun firstBatchFailed(player: HyperZonePlayer, reason: String, statusCode: Int?): Component {
        val fallback = if (statusCode != null) {
            "第一批次验证失败: $reason (HTTP $statusCode)"
        } else {
            "第一批次验证失败: $reason"
        }
        return render(
            player,
            if (statusCode != null) "first-batch-failed-with-status" else "first-batch-failed",
            fallback,
            HyperZoneMessagePlaceholder.text("reason", reason),
            HyperZoneMessagePlaceholder.text("status", statusCode ?: "")
        )
    }

    fun firstBatchTimeout(player: HyperZonePlayer): Component {
        return render(player, "first-batch-timeout", "第一批次验证超时")
    }

    private fun render(
        player: HyperZonePlayer,
        key: String,
        fallback: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(player, "$NAMESPACE.$key", *placeholders)
            ?: Component.text(placeholders.fold(fallback) { acc, placeholder ->
                val replacement = when (placeholder) {
                    is HyperZoneMessagePlaceholder.Text -> placeholder.value
                    is HyperZoneMessagePlaceholder.ComponentValue -> placeholder.value.toString()
                }
                acc.replace("<${placeholder.name}>", replacement)
            })
    }
}

