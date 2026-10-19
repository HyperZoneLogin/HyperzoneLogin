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

    fun usernameLengthInvalid(): Component = render("username.length-invalid")
    fun usernameWhitespaceInvalid(): Component = render("username.whitespace-invalid")
    fun usernamePatternInvalid(): Component = render("username.pattern-invalid")

    fun entryRejected(reason: Component): Component {
        return HyperZoneMessageServiceProvider.get().render(
            "$NAMESPACE.entry-rejected",
            HyperZoneMessagePlaceholder.component("reason", reason)
        )
    }

    fun authFailureCooldown(seconds: Long): Component {
        return render("auth-failure-cooldown",
            HyperZoneMessagePlaceholder.text("seconds", seconds)
        )
    }

    fun ipCooldown(seconds: Long): Component {
        return render("ip-cooldown",
            HyperZoneMessagePlaceholder.text("seconds", seconds)
        )
    }

    fun globalRateLimited(strictMode: Boolean): Component {
        return render(
            if (strictMode) "global-rate-limited.strict" else "global-rate-limited.normal"
        )
    }

    fun ipRateLimited(seconds: Long?): Component {
        return if (seconds != null) {
            render("ip-rate-limited.cooldown",
                HyperZoneMessagePlaceholder.text("seconds", seconds)
            )
        } else {
            render("ip-rate-limited.normal")
        }
    }

    fun ipRateLimitedStrict(): Component {
        return render("ip-rate-limited.strict")
    }

    fun ipConcurrentLimitReached(max: Int): Component {
        return render("ip-concurrent-limit",
            HyperZoneMessagePlaceholder.text("max", max)
        )
    }

    private fun render(
        key: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        return HyperZoneMessageServiceProvider.get().render("$NAMESPACE.$key", *placeholders)
    }
}

