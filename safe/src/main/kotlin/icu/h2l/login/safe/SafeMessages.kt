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

package icu.h2l.login.safe

import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import net.kyori.adventure.text.Component

object SafeMessages {
    private const val NAMESPACE = "safe"

    fun usernameLengthInvalid(): Component = render("username.length-invalid", "用户名长度不符合要求")
    fun usernameWhitespaceInvalid(): Component = render("username.whitespace-invalid", "用户名不能包含首尾空白")
    fun usernamePatternInvalid(): Component = render("username.pattern-invalid", "用户名包含不允许的字符")

    fun entryRejected(reason: Component): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(
            "$NAMESPACE.entry-rejected",
            HyperZoneMessagePlaceholder.component("reason", reason)
        ) ?: Component.text("连接已被入口防护拒绝：${reason}")
    }

    fun authFailureCooldown(seconds: Long): Component {
        return render(
            "auth-failure-cooldown",
            "该 IP 因认证失败过多已被临时限制，请在 <seconds> 秒后再试",
            HyperZoneMessagePlaceholder.text("seconds", seconds)
        )
    }

    fun ipCooldown(seconds: Long): Component {
        return render(
            "ip-cooldown",
            "你的 IP 已被临时限制，请在 <seconds> 秒后再试",
            HyperZoneMessagePlaceholder.text("seconds", seconds)
        )
    }

    fun globalRateLimited(strictMode: Boolean): Component {
        return render(
            if (strictMode) "global-rate-limited.strict" else "global-rate-limited.normal",
            if (strictMode) "当前连接请求过于频繁，请稍后再试（当前为高峰防护模式）" else "当前连接请求过于频繁，请稍后再试"
        )
    }

    fun ipRateLimited(seconds: Long?): Component {
        return if (seconds != null) {
            render(
                "ip-rate-limited.cooldown",
                "你的 IP 请求过于频繁，已被临时限制 <seconds> 秒",
                HyperZoneMessagePlaceholder.text("seconds", seconds)
            )
        } else {
            render("ip-rate-limited.normal", "你的 IP 请求过于频繁，请稍后再试")
        }
    }

    fun ipRateLimitedStrict(): Component {
        return render("ip-rate-limited.strict", "你的 IP 请求过于频繁，请稍后再试（当前为高峰防护模式）")
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
}

