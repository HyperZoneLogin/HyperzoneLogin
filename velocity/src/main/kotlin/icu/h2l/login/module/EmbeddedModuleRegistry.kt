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

package icu.h2l.login.module

import icu.h2l.api.module.HyperSubModule

data class EmbeddedModuleSpec(
    val configKey: String,
    val displayName: String,
    val externalPluginId: String,
    val implementationClassName: String,
    val requiredPluginIds: List<String> = emptyList(),
)

object EmbeddedModuleRegistry {
    val authFloodgate = EmbeddedModuleSpec(
        configKey = "authFloodgate",
        displayName = "Auth Floodgate",
        externalPluginId = "hzl-auth-floodgate",
        implementationClassName = "icu.h2l.login.auth.floodgate.FloodgateSubModule",
        requiredPluginIds = listOf("floodgate"),
    )

    val authOffline = EmbeddedModuleSpec(
        configKey = "authOffline",
        displayName = "Auth Offline",
        externalPluginId = "hzl-auth-offline",
        implementationClassName = "icu.h2l.login.auth.offline.OfflineSubModule",
    )

    val authYggd = EmbeddedModuleSpec(
        configKey = "authYggd",
        displayName = "Auth Yggdrasil",
        externalPluginId = "hzl-auth-yggd",
        implementationClassName = "icu.h2l.login.auth.online.main.YggdrasilSubModule",
    )

    val safe = EmbeddedModuleSpec(
        configKey = "safe",
        displayName = "Safe",
        externalPluginId = "hzl-safe",
        implementationClassName = "icu.h2l.login.safe.SafeSubModule",
    )

    val profileSkin = EmbeddedModuleSpec(
        configKey = "profileSkin",
        displayName = "Profile Skin",
        externalPluginId = "hzl-profile-skin",
        implementationClassName = "icu.h2l.login.profile.skin.ProfileSkinSubModule",
    )

    val dataMerge = EmbeddedModuleSpec(
        configKey = "dataMerge",
        displayName = "Data Merge",
        externalPluginId = "hzl-data-merge",
        implementationClassName = "icu.h2l.login.merge.MergeSubModule",
    )

    @Suppress("unused")
    val backendNanolimbo = EmbeddedModuleSpec(
        configKey = "backendNanolimbo",
        displayName = "Backend NanoLimbo",
        externalPluginId = "hzl-backend-nanolimbo",
        implementationClassName = "icu.h2l.login.backend.nanolimbo.NanoLimboSubModule",
    )

    fun instantiate(spec: EmbeddedModuleSpec, classLoader: ClassLoader): HyperSubModule? {
        val moduleClass = try {
            Class.forName(spec.implementationClassName, true, classLoader)
        } catch (_: ClassNotFoundException) {
            return null
        }

        require(HyperSubModule::class.java.isAssignableFrom(moduleClass)) {
            "${spec.implementationClassName} does not implement HyperSubModule"
        }

        val constructor = moduleClass.getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance() as HyperSubModule
    }
}


