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

package icu.h2l.login.player

import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.event.profile.ProfilePropertiesApplyEvent
import icu.h2l.api.log.error
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.util.debugGeneral
import java.util.concurrent.CompletableFuture

/**
 * 通用的档案属性应用支持。
 *
 * 触发 [ProfilePropertiesApplyEvent] 事件，让各功能模块（如皮肤模块）
 * 根据自己的需求操作 [GameProfile.Property] 列表。
 *
 * 核心层不理解具体的属性内容，只负责事件触发和最终档案构造。
 */
object ProfilePropertiesApplySupport {
    fun apply(hyperZonePlayer: HyperZonePlayer, baseProfile: GameProfile? = null): GameProfile? {
        return applyAsync(hyperZonePlayer, baseProfile).join()
    }

    fun applyAsync(
        hyperZonePlayer: HyperZonePlayer,
        baseProfile: GameProfile? = null
    ): CompletableFuture<GameProfile?> {
        val resolvedBaseProfile = resolveBaseProfile(hyperZonePlayer, baseProfile)
            ?: return CompletableFuture.completedFuture(null)

        val event = ProfilePropertiesApplyEvent(
            hyperZonePlayer = hyperZonePlayer,
            baseProfile = resolvedBaseProfile,
            properties = resolvedBaseProfile.properties.toMutableList()
        )

        return HyperZoneLoginMain.getInstance().proxy.eventManager.fire(event)
            .handle { _, throwable ->
                if (throwable != null) {
                    error(throwable) { "Profile properties apply event failed: ${throwable.message}" }
                    return@handle resolvedBaseProfile
                }

                GameProfile(
                    resolvedBaseProfile.id,
                    resolvedBaseProfile.name,
                    event.properties
                )
            }
    }

    private fun resolveBaseProfile(hyperZonePlayer: HyperZonePlayer, baseProfile: GameProfile?): GameProfile? {
        if (baseProfile != null) {
            return baseProfile
        }

        return runCatching {
            hyperZonePlayer.getAttachedGameProfile()
        }.getOrElse { throwable ->
            debugGeneral {
                "[ProfilePropertiesFlow] apply aborted: clientOriginal=${hyperZonePlayer.clientOriginalName}, reason=${throwable.message ?: throwable.javaClass.simpleName}, attachedProfile=${hyperZonePlayer.hasAttachedProfile()}"
            }
            null
        }
    }
}
