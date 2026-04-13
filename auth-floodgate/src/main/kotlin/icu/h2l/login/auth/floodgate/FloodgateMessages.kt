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

package icu.h2l.login.auth.floodgate

import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import icu.h2l.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component

object FloodgateMessages {
    private const val NAMESPACE = "auth-floodgate"

    fun initPlayerFailed(): Component {
        return render("init-player-failed", "Floodgate 登录失败：登录对象初始化失败。")
    }

    fun temporaryProfileFailed(): Component {
        return render("temporary-profile-failed", "Floodgate 登录失败：临时档案初始化失败。")
    }

    fun createBlocked(player: HyperZonePlayer): Component {
        return render(
            player,
            "create-blocked",
            "Floodgate 已完成可信认证，但当前注册名无法创建 Profile。请使用 /rename [新注册名] 或 /bindcode use [绑定码]。"
        )
    }

    fun completeFailed(player: HyperZonePlayer): Component {
        return render(player, "complete-failed", "Floodgate 登录失败：认证完成阶段出错。")
    }

    fun genericDisconnect(): Component {
        return render("generic-disconnect", "Floodgate 登录失败。")
    }

    private fun render(
        key: String,
        fallback: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render("$NAMESPACE.$key", *placeholders)
            ?: Component.text(placeholders.fold(fallback) { acc, placeholder ->
                val replacement = when (placeholder) {
                    is HyperZoneMessagePlaceholder.Text -> placeholder.value
                    is HyperZoneMessagePlaceholder.ComponentValue -> placeholder.value.toString()
                }
                acc.replace("<${placeholder.name}>", replacement)
            })
    }

    private fun render(
        player: HyperZonePlayer,
        key: String,
        fallback: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(player, "$NAMESPACE.$key", *placeholders)
            ?: render(key, fallback, *placeholders)
    }
}

