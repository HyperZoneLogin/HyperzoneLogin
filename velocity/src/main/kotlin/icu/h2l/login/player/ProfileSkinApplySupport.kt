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
import icu.h2l.api.event.profile.ProfileSkinApplyEvent
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.api.log.warn
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.login.HyperZoneLoginMain

object ProfileSkinApplySupport {
    fun apply(hyperZonePlayer: HyperZonePlayer, baseProfile: GameProfile? = null): GameProfile? {
        return applyAsync(hyperZonePlayer, baseProfile).join()
    }

    fun applyAsync(hyperZonePlayer: HyperZonePlayer, baseProfile: GameProfile? = null): java.util.concurrent.CompletableFuture<GameProfile?> {
        val resolvedBaseProfile = resolveBaseProfile(hyperZonePlayer, baseProfile)
            ?: return java.util.concurrent.CompletableFuture.completedFuture(null)
        val event = ProfileSkinApplyEvent(hyperZonePlayer, resolvedBaseProfile)

        return HyperZoneLoginMain.getInstance().proxy.eventManager.fire(event)
            .handle { _, throwable ->
                if (throwable != null) {
                    error(throwable) { "Profile skin apply event failed: ${throwable.message}" }
                    return@handle resolvedBaseProfile
                }

                val textures = event.textures ?: return@handle resolvedBaseProfile
                val property = textures.toPropertyOrNull() ?: run {
                    warn {
                        "[ProfileSkinFlow] apply skipped incomplete textures: clientOriginal=${hyperZonePlayer.clientOriginalName}, profile=${resolvedBaseProfile.id}, valueLength=${textures.value.length}, signed=${textures.isSigned}"
                    }
                    return@handle resolvedBaseProfile
                }
                mergeTextures(resolvedBaseProfile, property)
            }
    }

    private fun resolveBaseProfile(hyperZonePlayer: HyperZonePlayer, baseProfile: GameProfile?): GameProfile? {
        if (baseProfile != null) {
            return baseProfile
        }

        return runCatching {
            hyperZonePlayer.getAttachedGameProfile()
        }.getOrElse { throwable ->
            debug(HyperZoneDebugType.PROFILE_SKIN) {
                "[ProfileSkinFlow] apply aborted: clientOriginal=${hyperZonePlayer.clientOriginalName}, reason=${throwable.message ?: throwable.javaClass.simpleName}, waitingArea=${hyperZonePlayer.isInWaitingArea()}, attachedProfile=${hyperZonePlayer.hasAttachedProfile()}"
            }
            null
        }
    }

    internal fun mergeTextures(baseProfile: GameProfile, textureProperty: GameProfile.Property): GameProfile {
        val mergedProperties = baseProfile.properties
            .filterNot { it.name.equals("textures", ignoreCase = true) }
            .toMutableList()
            .apply { add(textureProperty) }

        return GameProfile(
            baseProfile.id,
            baseProfile.name,
            mergedProperties
        )
    }
}


