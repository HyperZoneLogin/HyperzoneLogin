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

package icu.h2l.login.auth.floodgate.service

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.api.profile.HyperZoneProfileServiceProvider
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.auth.floodgate.config.FloodgateAuthConfig
import icu.h2l.login.auth.floodgate.FloodgateMessages
import icu.h2l.login.auth.floodgate.credential.FloodgateHyperZoneCredential
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import java.util.UUID

class FloodgateAuthService(
    private val api: HyperZoneApi,
    private val floodgateApiHolder: FloodgateApiHolder,
    private val sessionHolder: FloodgateSessionHolder,
    private val config: FloodgateAuthConfig = FloodgateAuthConfig(),
    private val profileService: HyperZoneProfileService = HyperZoneProfileServiceProvider.get()
) {
    private val logger = java.util.logging.Logger.getLogger("hzl-auth-floodgate")

    sealed interface VerifyResult {
        data object NotFloodgate : VerifyResult
        data object Accepted : VerifyResult
        data class Failed(val userMessage: Component) : VerifyResult
    }

    data class CompleteResult(
        val handled: Boolean,
        val passed: Boolean,
        val disconnectOnFailure: Boolean = false,
        val userMessage: Component? = null
    )

    /**
     * Floodgate 会跳过 HZL 自订的 OpenPreLogin/OpenStartAuth 事件，
     * 因此这里只负责：识别 Floodgate、创建登录期玩家对象、记录会话。
     */
    fun acceptInitialProfile(channel: Channel, userName: String, userUUID: UUID): VerifyResult {
        if (!floodgateApiHolder.isFloodgatePlayer(userUUID)) {
            return VerifyResult.NotFloodgate
        }

        val normalizedUserName = normalizeUserName(userName)

        sessionHolder.remember(channel, normalizedUserName, userUUID)

        val hyperZonePlayer = try {
            api.hyperZonePlayers.create(channel, normalizedUserName, userUUID, FLOODGATE_CHANNEL_PLACEHOLDER_MODE)
        } catch (throwable: Throwable) {
            val isDuplicateCreate = throwable.message?.contains("重复创建 HyperZonePlayer") == true
            if (isDuplicateCreate) {
                runCatching { api.hyperZonePlayers.getByChannel(channel) }.getOrElse { lookupError ->
                    logger.warning(
                        "Floodgate 玩家 $normalizedUserName($userUUID) 初始化登录对象重复后回收失败: ${lookupError.message}"
                    )
                    sessionHolder.remove(channel)
                    return VerifyResult.Failed(FloodgateMessages.initPlayerFailed())
                }
            } else {
                logger.warning("Floodgate 玩家 $normalizedUserName($userUUID) 初始化登录对象失败: ${throwable.message}")
                sessionHolder.remove(channel)
                return VerifyResult.Failed(FloodgateMessages.initPlayerFailed())
            }
        }

        try {
            hyperZonePlayer.setTemporaryGameProfile(RemapUtils.randomProfile())
        } catch (throwable: Throwable) {
            logger.warning("Floodgate 玩家 $normalizedUserName($userUUID) 生成临时档案失败: ${throwable.message}")
            sessionHolder.remove(channel)
            return VerifyResult.Failed(FloodgateMessages.temporaryProfileFailed())
        }

        return VerifyResult.Accepted
    }

    fun complete(channel: Channel, hyperZonePlayer: HyperZonePlayer): CompleteResult {
        val session = sessionHolder.get(channel)
        if (session == null && !hasFloodgateCredential(hyperZonePlayer)) {
            return CompleteResult(handled = false, passed = false)
        }

        return try {
            if (session != null && findCredential(hyperZonePlayer, session.userUUID) == null) {
                val suggestedProfileCreateUuid = resolveProfileUuid(session.userUUID)
                val knownProfileId = profileService.getAttachedProfile(hyperZonePlayer)?.id
                    ?: if (profileService.canCreate(hyperZonePlayer.registrationName, suggestedProfileCreateUuid)) {
                        profileService.create(hyperZonePlayer.registrationName, suggestedProfileCreateUuid).id
                    } else {
                        null
                    }
                hyperZonePlayer.submitCredential(
                    FloodgateHyperZoneCredential(
                        trustedName = session.userName,
                        trustedUuid = session.userUUID,
                        suggestedProfileCreateUuid = suggestedProfileCreateUuid,
                        knownProfileId = knownProfileId
                    )
                )

                if (knownProfileId == null) {
                    return CompleteResult(
                        handled = true,
                        passed = false,
                        disconnectOnFailure = false,
                        userMessage = FloodgateMessages.createBlocked(hyperZonePlayer)
                    )
                }
            }
            hyperZonePlayer.overVerify()
            sessionHolder.remove(channel)
            CompleteResult(handled = true, passed = true)
        } catch (throwable: Throwable) {
            logger.warning("Floodgate 玩家 ${hyperZonePlayer.clientOriginalName} 完成认证失败: ${throwable.message}")
            CompleteResult(
                handled = true,
                passed = false,
                disconnectOnFailure = true,
                userMessage = FloodgateMessages.completeFailed(hyperZonePlayer)
            )
        }
    }

    private fun hasFloodgateCredential(hyperZonePlayer: HyperZonePlayer): Boolean {
        return hyperZonePlayer.getSubmittedCredentials().any { it is FloodgateHyperZoneCredential }
    }

    private fun findCredential(hyperZonePlayer: HyperZonePlayer, userUUID: UUID): FloodgateHyperZoneCredential? {
        return hyperZonePlayer.getSubmittedCredentials()
            .asSequence()
            .filterIsInstance<FloodgateHyperZoneCredential>()
            .firstOrNull { it.matches(userUUID) }
    }

    fun clear(channel: Channel) {
        sessionHolder.remove(channel)
    }

    private fun normalizeUserName(userName: String): String {
        if (!config.stripUsernamePrefix) {
            return userName
        }

        val playerPrefix = floodgateApiHolder.getPlayerPrefix()
        if (playerPrefix.isBlank() || !userName.startsWith(playerPrefix)) {
            return userName
        }

        val stripped = userName.removePrefix(playerPrefix)
        return stripped.ifEmpty { userName }
    }

    private fun resolveProfileUuid(userUUID: UUID): UUID? {
        return if (config.passFloodgateUuidToProfileResolve) userUUID else null
    }

    companion object {
        /**
         * Floodgate 作为独立渠道会跳过自订 OpenPreLogin/OpenStartAuth，
         * 这里仅传入一个占位布尔值以满足现有 HyperZonePlayer 创建签名；
         * 不应把它解读为 Floodgate 的在线/离线语义。
         */
        private const val FLOODGATE_CHANNEL_PLACEHOLDER_MODE = false
    }
}

