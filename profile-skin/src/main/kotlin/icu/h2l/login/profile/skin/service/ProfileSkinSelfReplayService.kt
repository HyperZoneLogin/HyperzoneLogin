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

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.configuration.PlayerFinishConfigurationEvent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.protocol.packet.LegacyPlayerListItemPacket
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket
import icu.h2l.api.event.profile.ProfileSkinPreprocessEvent
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.api.log.warn
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.api.profile.skin.ProfileSkinTextures
import icu.h2l.login.profile.skin.config.ProfileSkinConfig
import icu.h2l.login.profile.skin.db.ProfileSkinCacheRepository
import icu.h2l.login.profile.skin.db.ProfileSkinProfileRepository
import io.netty.channel.EventLoop
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// ── MinecraftConnection 反射工具（避免直接 import ConnectedPlayer）─────────────

private val connectionFieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()

/**
 * 从 [Player] 实例中反射获取内部 `MinecraftConnection` 对象。
 * 结果按类型缓存，避免重复查找。
 */
private fun Player.minecraftConnection(): Any {
    val field = connectionFieldCache.getOrPut(javaClass) {
        generateSequence<Class<*>>(javaClass) { it.superclass }
            .firstNotNullOfOrNull { cls ->
                runCatching { cls.getDeclaredField("connection").also { it.isAccessible = true } }.getOrNull()
            } ?: error("Cannot find 'connection' field in ${javaClass.name}")
    }
    return field.get(this)
}

private fun Any.mcIsClosed(): Boolean =
    javaClass.getMethod("isClosed").invoke(this) as Boolean

private fun Any.mcEventLoop(): EventLoop =
    javaClass.getMethod("eventLoop").invoke(this) as EventLoop

private fun Any.mcWrite(packet: Any) {
    javaClass.getMethod("write", Any::class.java).invoke(this, packet)
}

// ─────────────────────────────────────────────────────────────────────────────

private class SelfSkinReplayState {
    val selfAddPlayerSent = AtomicBoolean(false)
    val latestTextures = AtomicReference<ProfileSkinTextures?>(null)
}

class ProfileSkinSelfReplayService(
    private val playerAccessor: HyperZonePlayerAccessor,
    private val config: ProfileSkinConfig,
    private val cacheRepository: ProfileSkinCacheRepository,
    private val profileRepository: ProfileSkinProfileRepository,
    private val profileService: HyperZoneProfileService
) {
    private val replayStates = ConcurrentHashMap<HyperZonePlayer, SelfSkinReplayState>()

    @Subscribe(priority = (Short.MIN_VALUE + 1).toShort())
    fun onProfileSkinPreprocess(event: ProfileSkinPreprocessEvent) {
        /**
         * 这里的职责仅限于“保存第一次拿到的上游材质”。
         *
         * 注意：
         * 1. 不在这里改写 `ProfileSkinPreprocessEvent.textures`；
         * 2. 不在这里做 profile 缓存回退；
         * 3. 不把这里当成通用皮肤决策入口，只把最初拿到的材质快照下来，供后续 self replay 使用。
         */
        val textures = event.textures ?: return
        if (textures.value.isBlank()) {
            return
        }

        val state = stateFor(event.hyperZonePlayer)
        state.latestTextures.compareAndSet(null, textures)
    }

    @Subscribe(priority = Short.MIN_VALUE)
    fun onProfileSkinPreprocessInitialSend(event: ProfileSkinPreprocessEvent) {
        val state = replayStates[event.hyperZonePlayer] ?: return
        val textures = state.latestTextures.get()?.takeIf(::canReplayTextures) ?: return
        if (state.selfAddPlayerSent.get()) {
            return
        }

        val player = event.hyperZonePlayer.getProxyPlayerOrNull() ?: return
        sendSelfAddPlayer(
            hyperZonePlayer = event.hyperZonePlayer,
            player = player,
            textures = textures,
            forceReplay = false,
            failureLabel = "Preprocess",
            state = state,
        )
    }

    @Subscribe
    fun onPlayerFinishConfiguration(event: PlayerFinishConfigurationEvent) {
        val hyperZonePlayer = runCatching {
            playerAccessor.getByPlayer(event.player())
        }.getOrNull() ?: return
        val state = stateFor(hyperZonePlayer)
        val textures = resolveReplayTextures(
            hyperZonePlayer,
            preferredTextures = state.latestTextures.get()
        ) ?: return
        state.latestTextures.set(textures)

        val player = event.player() ?: return
        sendSelfAddPlayer(
            hyperZonePlayer = hyperZonePlayer,
            player = player,
            textures = textures,
            forceReplay = true,
            failureLabel = "Post-configuration replay",
            state = state,
        )
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        runCatching { playerAccessor.getByPlayer(event.player) }
            .getOrNull()?.let { replayStates.remove(it) }
    }

    private fun stateFor(hyperZonePlayer: HyperZonePlayer): SelfSkinReplayState =
        replayStates.computeIfAbsent(hyperZonePlayer) { SelfSkinReplayState() }

    private fun resolveReplayTextures(
        hyperZonePlayer: HyperZonePlayer,
        preferredTextures: ProfileSkinTextures?,
    ): ProfileSkinTextures? {
        val preferred = preferredTextures?.takeIf(::canReplayTextures)
        if (preferred != null) return preferred

        val profileId = profileService.getAttachedProfile(hyperZonePlayer)?.id ?: return null
        val skinId = profileRepository.findSkinIdByProfileId(profileId) ?: return null
        val cached = cacheRepository.findBySkinId(skinId)?.textures?.takeIf(::canReplayTextures) ?: return null
        debug(HyperZoneDebugType.PROFILE_SKIN) {
            "[ProfileSkinFlow] self replay fallback to cached profile textures: clientOriginal=${hyperZonePlayer.clientOriginalName}, profile=$profileId, skin=$skinId, valueLength=${cached.value.length}, signed=${cached.isSigned}"
        }
        return cached
    }

    private fun canReplayTextures(textures: ProfileSkinTextures): Boolean =
        textures.value.isNotBlank() && textures.toPropertyOrNull() != null

    private fun sendSelfAddPlayer(
        hyperZonePlayer: HyperZonePlayer,
        player: Player,
        textures: ProfileSkinTextures,
        forceReplay: Boolean,
        failureLabel: String,
        state: SelfSkinReplayState,
    ) {
        if (!forceReplay && state.selfAddPlayerSent.get()) return

        val connection = runCatching { player.minecraftConnection() }.getOrNull() ?: return
        if (!player.isActive || connection.mcIsClosed()) return

        val property = textures.toPropertyOrNull() ?: run {
            warn {
                "[ProfileSkinFlow] $failureLabel self ADD_PLAYER skipped due to incomplete textures: clientOriginal=${hyperZonePlayer.clientOriginalName}, valueLength=${textures.value.length}, signed=${textures.isSigned}"
            }
            return
        }

        if (!forceReplay && !state.selfAddPlayerSent.compareAndSet(false, true)) return

        val replayProfile = GameProfile(
            hyperZonePlayer.clientOriginalUUID,
            hyperZonePlayer.clientOriginalName,
            listOf(property),
        )

        connection.mcEventLoop().execute {
            try {
                if (!player.isActive || connection.mcIsClosed()) {
                    if (!forceReplay) state.selfAddPlayerSent.set(false)
                    return@execute
                }
                SelfPlayerInfoSkinSender.sendAddPlayer(player, replayProfile, connection)
                state.selfAddPlayerSent.set(true)
            } catch (throwable: Throwable) {
                if (!forceReplay) state.selfAddPlayerSent.set(false)
                error(throwable) {
                    "$failureLabel self ADD_PLAYER failed for clientOriginal=${hyperZonePlayer.clientOriginalName}: ${throwable.message}"
                }
            }
        }
    }
}

private object SelfPlayerInfoSkinSender {
    /**
     * @param connection 已通过反射获取的 MinecraftConnection 实例
     */
    fun sendAddPlayer(player: Player, profile: GameProfile, connection: Any) {
        val protocolVersion = player.protocolVersion
        when {
            protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_19_3) ->
                connection.mcWrite(createModernAddPlayer(profile))

            protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_8) ->
                connection.mcWrite(createLegacyAddPlayer(profile))

            else -> debug(HyperZoneDebugType.PROFILE_SKIN) {
                "[ProfileSkinFlow] self ADD_PLAYER skipped: unsupported protocol for skin properties, player=${player.username}, protocol=$protocolVersion"
            }
        }
    }

    private fun createModernAddPlayer(profile: GameProfile): UpsertPlayerInfoPacket {
        val entry = UpsertPlayerInfoPacket.Entry(profile.id)
        entry.profile = profile
        entry.latency = 0
        entry.isListed = true
        return UpsertPlayerInfoPacket(
            EnumSet.of(
                UpsertPlayerInfoPacket.Action.ADD_PLAYER,
                UpsertPlayerInfoPacket.Action.UPDATE_LATENCY,
                UpsertPlayerInfoPacket.Action.UPDATE_LISTED,
            ),
            listOf(entry),
        )
    }

    private fun createLegacyAddPlayer(profile: GameProfile): LegacyPlayerListItemPacket {
        val item = LegacyPlayerListItemPacket.Item(profile.id)
            .setName(profile.name)
            .setProperties(profile.properties)
            .setGameMode(0)
            .setLatency(0)
        return LegacyPlayerListItemPacket(LegacyPlayerListItemPacket.ADD_PLAYER, listOf(item))
    }
}
