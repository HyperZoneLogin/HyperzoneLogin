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

package icu.h2l.login.auth.online.message

import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import icu.h2l.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component

object YggdrasilMessages {
    private const val NAMESPACE = "auth-yggd"

    /**
     * 返回"注册已被禁用"的原因文本，用于作为 [profileResolveFailed] 的 reason 参数。
     */
    fun registrationDisabledReason(player: HyperZonePlayer): String {
        val component = HyperZoneMessageServiceProvider.get().render(player, "$NAMESPACE.registration-disabled-reason")
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)
    }

    fun authInProgress(player: HyperZonePlayer): Component {
        return render(player, "auth-in-progress")
    }

    fun authSucceeded(player: HyperZonePlayer): Component {
        return render(player, "auth-succeeded")
    }

    fun profileResolveFailed(player: HyperZonePlayer, reason: String): Component {
        return render(player, "profile-resolve-failed",
            HyperZoneMessagePlaceholder.text("reason", reason)
        )
    }

    fun verifyCompleteFailed(player: HyperZonePlayer, reason: String): Component {
        return render(player, "verify-complete-failed",
            HyperZoneMessagePlaceholder.text("reason", reason)
        )
    }

    fun authFailed(player: HyperZonePlayer, reason: String): Component {
        return render(player, "auth-failed", HyperZoneMessagePlaceholder.text("reason", reason))
    }

    fun firstBatchFailed(player: HyperZonePlayer, reason: String, statusCode: Int?): Component {
        return render(
            player,
            if (statusCode != null) "first-batch-failed-with-status" else "first-batch-failed",
            HyperZoneMessagePlaceholder.text("reason", reason),
            HyperZoneMessagePlaceholder.text("status", statusCode ?: "")
        )
    }

    fun firstBatchTimeout(player: HyperZonePlayer): Component {
        return render(player, "first-batch-timeout")
    }

    private fun render(
        player: HyperZonePlayer,
        key: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        return HyperZoneMessageServiceProvider.get().render(player, "$NAMESPACE.$key", *placeholders)
    }
}
