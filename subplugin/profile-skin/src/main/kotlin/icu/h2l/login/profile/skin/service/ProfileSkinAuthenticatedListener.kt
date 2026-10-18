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

package icu.h2l.login.profile.skin.service

import com.google.gson.Gson
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.event.profile.ProfileAuthenticatedEvent
import icu.h2l.login.profile.skin.config.ProfileSkinDebug
import icu.h2l.login.profile.skin.data.ProfileSkinModel
import icu.h2l.login.profile.skin.data.ProfileSkinSource
import icu.h2l.login.profile.skin.data.ProfileSkinTextures
import icu.h2l.login.profile.skin.event.ProfileSkinPreprocessEvent
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * 皮肤模块对外部认证完成事件的监听入口
 *
 * 监听 API 层的通用认证事件 [ProfileAuthenticatedEvent]，
 * 从认证档案中提取皮肤信息，然后触发模块内部的 [ProfileSkinPreprocessEvent]，
 * 供模块内其他服务处理。
 */
class ProfileSkinAuthenticatedListener(
    private val proxy: ProxyServer
) {
    private val gson: Gson = Gson()

    @Subscribe
    fun onAuthenticated(event: ProfileAuthenticatedEvent) {
        val textures = extractTextures(event.authenticatedProfile)
        val source = extractSkinSource(textures)

        ProfileSkinDebug.log {
            "[ProfileSkinFlow] authenticated event received: clientOriginal=${event.hyperZonePlayer.clientOriginalName}, entry=${event.entryId}, server=${event.serverUrl}, textures=${describeTextures(textures)}, source=${describeSource(source)}"
        }

        val preprocessEvent = ProfileSkinPreprocessEvent(
            hyperZonePlayer = event.hyperZonePlayer,
            authenticatedProfile = event.authenticatedProfile,
            entryId = event.entryId,
            serverUrl = event.serverUrl
        )
        preprocessEvent.textures = textures
        preprocessEvent.source = source

        proxy.eventManager.fire(preprocessEvent).join()
    }

    private fun extractTextures(profile: GameProfile): ProfileSkinTextures? {
        val property = profile.properties.firstOrNull { it.name.equals("textures", ignoreCase = true) } ?: return null
        return ProfileSkinTextures(property.value, property.signature)
    }

    private fun extractSkinSource(textures: ProfileSkinTextures?): ProfileSkinSource? {
        val value = textures?.value ?: return null
        val decoded = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
        val root = gson.fromJson(decoded, Map::class.java)
        val texturesMap = root["textures"] as? Map<*, *> ?: return null
        val skinMap = texturesMap["SKIN"] as? Map<*, *> ?: return null
        val url = skinMap["url"] as? String ?: return null
        val metadata = skinMap["metadata"] as? Map<*, *>
        val model = metadata?.get("model") as? String
        return ProfileSkinSource(url, ProfileSkinModel.normalize(model))
    }

    private fun describeTextures(textures: ProfileSkinTextures?): String {
        if (textures == null) return "none"
        return "present(valueLength=${textures.value.length}, signed=${textures.isSigned})"
    }

    private fun describeSource(source: ProfileSkinSource?): String {
        if (source == null) return "none"
        return "url=${source.skinUrl}, model=${source.model}"
    }
}
