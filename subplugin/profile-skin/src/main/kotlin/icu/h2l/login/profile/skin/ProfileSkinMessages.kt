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

package icu.h2l.login.profile.skin

import icu.h2l.api.message.HyperZoneMessageServiceProvider
import icu.h2l.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component

object ProfileSkinMessages {
    private const val NAMESPACE = "profile-skin"

    fun processing(player: HyperZonePlayer): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(player, "$NAMESPACE.processing")
            ?: Component.text("正在处理皮肤信息，请稍候…")
    }

    fun ready(player: HyperZonePlayer): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(player, "$NAMESPACE.ready")
            ?: Component.text("皮肤信息处理完成，进入游戏后将自动应用。")
    }

    fun readyWithFallback(player: HyperZonePlayer): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(player, "$NAMESPACE.ready-with-fallback")
            ?: Component.text("皮肤信息处理完成，但使用了回退结果，显示可能不稳定。")
    }

    fun noSkin(player: HyperZonePlayer): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(player, "$NAMESPACE.no-skin")
            ?: Component.text("未检测到可用皮肤信息，将使用默认外观。")
    }

    fun repairFailed(player: HyperZonePlayer): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(player, "$NAMESPACE.repair-failed")
            ?: Component.text("皮肤修复失败，需要重新进入游戏")
    }
}

