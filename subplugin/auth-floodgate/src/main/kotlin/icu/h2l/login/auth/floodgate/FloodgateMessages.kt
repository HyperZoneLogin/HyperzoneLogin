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
        return render("init-player-failed")
    }


    fun registrationDisabled(player: HyperZonePlayer): Component {
        return render(player, "registration-disabled")
    }

    fun registrationDisabledByName(playerName: String): Component {
        return render("registration-disabled",
            HyperZoneMessagePlaceholder.text("player", playerName)
        )
    }

    fun createBlocked(player: HyperZonePlayer): Component {
        return render(player, "create-blocked")
    }

    fun createBlockedByName(playerName: String): Component {
        return render("create-blocked",
            HyperZoneMessagePlaceholder.text("player", playerName)
        )
    }

    fun completeFailed(player: HyperZonePlayer): Component {
        return render(player, "complete-failed")
    }

    fun completeFailedByName(playerName: String): Component {
        return render("complete-failed",
            HyperZoneMessagePlaceholder.text("player", playerName)
        )
    }

    fun genericDisconnect(): Component {
        return render("generic-disconnect")
    }

    private fun render(
        key: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        return HyperZoneMessageServiceProvider.get().render("$NAMESPACE.$key", *placeholders)
    }

    private fun render(
        player: HyperZonePlayer,
        key: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        return HyperZoneMessageServiceProvider.get().render(player, "$NAMESPACE.$key", *placeholders)
    }
}

