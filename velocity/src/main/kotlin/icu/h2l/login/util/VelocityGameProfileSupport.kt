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

package icu.h2l.login.util

import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import icu.h2l.api.db.InGameProfile
import icu.h2l.login.reflect.VelocityInternalAccess
import java.util.*
import java.util.concurrent.CompletableFuture

internal fun buildDeliveredGameProfile(
    currentGameProfile: GameProfile,
    activeIdentity: InGameProfile,
    enableNameHotChange: Boolean,
    enableUuidHotChange: Boolean,
): GameProfile {
    val resolvedName = if (enableNameHotChange) activeIdentity.name else currentGameProfile.name
    val resolvedUuid = if (enableUuidHotChange) activeIdentity.uuid else currentGameProfile.id
    return GameProfile(resolvedUuid, resolvedName, currentGameProfile.properties)
}

internal fun buildAttachedIdentityGameProfile(
    currentGameProfile: GameProfile,
    activeIdentity: InGameProfile,
): GameProfile {
    return GameProfile(activeIdentity.uuid, activeIdentity.name, currentGameProfile.properties)
}

internal fun hasSemanticGameProfileDifference(expected: GameProfile, actual: GameProfile): Boolean {
    if (expected.id != actual.id || expected.name != actual.name) {
        return true
    }

    return normalizeGameProfileProperties(expected) != normalizeGameProfileProperties(actual)
}

internal fun describeGameProfileBrief(profile: GameProfile): String {
    val propertyNames = profile.properties
        .map { it.name }
        .distinct()
        .sorted()
    return "id=${profile.id}, name=${profile.name}, propertyCount=${profile.properties.size}, propertyNames=$propertyNames"
}

private fun normalizeGameProfileProperties(profile: GameProfile): List<NormalizedGameProfileProperty> {
    return profile.properties
        .map { property ->
            NormalizedGameProfileProperty(
                name = property.name,
                value = property.value,
                signature = property.signature,
            )
        }
        .sortedWith(
            compareBy<NormalizedGameProfileProperty> { it.name }
                .thenBy { it.value }
                .thenBy { it.signature ?: "" }
        )
}

private data class NormalizedGameProfileProperty(
    val name: String,
    val value: String,
    val signature: String?,
)

internal fun setConnectedPlayerGameProfile(player: ConnectedPlayer, profile: GameProfile) {
    VelocityInternalAccess.setConnectedPlayerProfile(player, profile)
}

internal fun <T> executeOnPlayerEventLoop(player: ConnectedPlayer, action: () -> T): T {
    val eventLoop = player.connection.eventLoop()
    if (eventLoop.inEventLoop()) {
        return action()
    }

    val future = CompletableFuture<T>()
    eventLoop.execute {
        runCatching(action)
            .onSuccess(future::complete)
            .onFailure(future::completeExceptionally)
    }
    return future.join()
}

/**
 * Velocity 内部玩家数据反射访问，所有字段查找已委托给 [VelocityInternalAccess]。
 */
internal object VelocityGameProfileReflection {

    fun connectionsByName(server: VelocityServer): MutableMap<String, ConnectedPlayer> =
        VelocityInternalAccess.connectionsByName(server)

    fun connectionsByUuid(server: VelocityServer): MutableMap<UUID, ConnectedPlayer> =
        VelocityInternalAccess.connectionsByUuid(server)

    @Suppress("UNCHECKED_CAST")
    fun players(registeredServer: Any): MutableMap<UUID, ConnectedPlayer> =
        VelocityInternalAccess.registeredServerPlayers(registeredServer)
}
