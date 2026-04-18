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
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.api.profile.HyperZoneProfileServiceProvider
import icu.h2l.login.auth.floodgate.config.FloodgateAuthConfig
import icu.h2l.login.auth.floodgate.db.FloodgateAuthRepository
import icu.h2l.login.auth.floodgate.FloodgateMessages
import icu.h2l.login.auth.floodgate.credential.FloodgateHyperZoneCredential
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import java.util.UUID

class FloodgateAuthService(
    private val api: HyperZoneApi,
    private val floodgateApiHolder: FloodgateApiHolder,
    private val sessionHolder: FloodgateSessionHolder,
    private val repository: FloodgateAuthRepository,
    private val config: FloodgateAuthConfig = FloodgateAuthConfig(),
    private val profileService: HyperZoneProfileService = HyperZoneProfileServiceProvider.get()
) {
    private val logger = java.util.logging.Logger.getLogger("hzl-auth-floodgate")

    private fun trace(message: String) {
        debug(HyperZoneDebugType.OUTPRE_TRACE, message)
    }

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
    fun acceptInitialProfile(channel: Channel, userName: String, userUUID: UUID, xuid: Long): VerifyResult {
        trace(
            "acceptInitialProfile start channel=$channel userName=$userName userUUID=$userUUID xuid=$xuid adapter=${api.serverAdapter?.javaClass?.name ?: "null"}"
        )
        if (!floodgateApiHolder.isFloodgatePlayer(userUUID)) {
            trace("acceptInitialProfile ignored: not floodgate channel=$channel userUUID=$userUUID")
            return VerifyResult.NotFloodgate
        }

        val normalizedUserName = normalizeUserName(userName)
        trace(
            "acceptInitialProfile floodgate detected channel=$channel rawName=$userName normalizedName=$normalizedUserName userUUID=$userUUID xuid=$xuid"
        )

        sessionHolder.remember(channel, normalizedUserName, userUUID, xuid)
        trace("acceptInitialProfile session remembered channel=$channel normalizedName=$normalizedUserName userUUID=$userUUID xuid=$xuid")

        try {
            api.hyperZonePlayers.create(channel, normalizedUserName, userUUID, FLOODGATE_CHANNEL_PLACEHOLDER_MODE)
            trace("acceptInitialProfile hyper player created channel=$channel normalizedName=$normalizedUserName userUUID=$userUUID")
        } catch (throwable: Throwable) {
            val isDuplicateCreate = throwable.message?.contains("重复创建 HyperZonePlayer") == true
            if (isDuplicateCreate) {
                trace("acceptInitialProfile duplicate hyper player create channel=$channel normalizedName=$normalizedUserName userUUID=$userUUID")
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


        trace("acceptInitialProfile accepted channel=$channel normalizedName=$normalizedUserName userUUID=$userUUID")

        return VerifyResult.Accepted
    }

    fun complete(channel: Channel, hyperZonePlayer: HyperZonePlayer): CompleteResult {
        val session = sessionHolder.get(channel)
        trace(
            "complete start channel=$channel player=${hyperZonePlayer.clientOriginalName} sessionPresent=${session != null} waitingArea=${hyperZonePlayer.isInWaitingArea()} verified=${hyperZonePlayer.isVerified()} attachedProfile=${profileService.getAttachedProfile(hyperZonePlayer)?.id} credentialTypes=${hyperZonePlayer.getSubmittedCredentials().map { it.javaClass.simpleName }}"
        )
        if (session == null && !hasFloodgateCredential(hyperZonePlayer)) {
            trace("complete ignored channel=$channel player=${hyperZonePlayer.clientOriginalName}: no session and no floodgate credential")
            return CompleteResult(handled = false, passed = false)
        }

        return try {
            if (session != null && findCredential(hyperZonePlayer, session.userUUID) == null) {
                val suggestedProfileCreateUuid = resolveProfileUuid(session.userUUID)
                val knownProfileId = resolveKnownProfileId(hyperZonePlayer, session)
                    ?: createAndBindProfileIfAllowed(hyperZonePlayer, session, suggestedProfileCreateUuid)
                trace(
                    "complete preparing credential channel=$channel player=${hyperZonePlayer.clientOriginalName} sessionName=${session.userName} sessionUuid=${session.userUUID} xuid=${session.xuid} suggestedProfileCreateUuid=$suggestedProfileCreateUuid knownProfileId=$knownProfileId"
                )
                hyperZonePlayer.submitCredential(
                    FloodgateHyperZoneCredential(
                        repository = repository,
                        trustedName = session.userName,
                        trustedUuid = session.userUUID,
                        xuid = session.xuid,
                        suggestedProfileCreateUuid = suggestedProfileCreateUuid,
                        knownProfileId = knownProfileId
                    )
                )
                trace(
                    "complete credential submitted channel=$channel player=${hyperZonePlayer.clientOriginalName} credentialTypes=${hyperZonePlayer.getSubmittedCredentials().map { it.javaClass.simpleName }}"
                )

                if (knownProfileId == null) {
                    trace("complete blocked channel=$channel player=${hyperZonePlayer.clientOriginalName}: no known profile id")
                    return CompleteResult(
                        handled = true,
                        passed = false,
                        disconnectOnFailure = false,
                        userMessage = FloodgateMessages.createBlocked(hyperZonePlayer)
                    )
                }
            }
            trace(
                "complete before overVerify channel=$channel player=${hyperZonePlayer.clientOriginalName} waitingArea=${hyperZonePlayer.isInWaitingArea()} attachedProfile=${profileService.getAttachedProfile(hyperZonePlayer)?.id}"
            )
            hyperZonePlayer.overVerify()
            trace(
                "complete after overVerify channel=$channel player=${hyperZonePlayer.clientOriginalName} waitingArea=${hyperZonePlayer.isInWaitingArea()} verified=${hyperZonePlayer.isVerified()} attachedProfile=${profileService.getAttachedProfile(hyperZonePlayer)?.id}"
            )
            sessionHolder.remove(channel)
            trace("complete success channel=$channel player=${hyperZonePlayer.clientOriginalName} sessionCleared=true")
            CompleteResult(handled = true, passed = true)
        } catch (throwable: Throwable) {
            logger.warning("Floodgate 玩家 ${hyperZonePlayer.clientOriginalName} 完成认证失败: ${throwable.message}")
            trace(
                "complete failed channel=$channel player=${hyperZonePlayer.clientOriginalName} waitingArea=${hyperZonePlayer.isInWaitingArea()} verified=${hyperZonePlayer.isVerified()} attachedProfile=${profileService.getAttachedProfile(hyperZonePlayer)?.id} error=${throwable.message}"
            )
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

    private fun resolveKnownProfileId(
        hyperZonePlayer: HyperZonePlayer,
        session: FloodgateSessionHolder.FloodgateSession,
    ): UUID? {
        val attachedProfileId = profileService.getAttachedProfile(hyperZonePlayer)?.id
        if (attachedProfileId != null) {
            repository.createOrUpdate(session.userName, session.xuid, attachedProfileId)
            return attachedProfileId
        }

        val repositoryProfileId = repository.findProfileIdByXuid(session.xuid) ?: return null
        repository.updateEntryName(session.xuid, session.userName)
        return repositoryProfileId
    }

    private fun createAndBindProfileIfAllowed(
        hyperZonePlayer: HyperZonePlayer,
        session: FloodgateSessionHolder.FloodgateSession,
        suggestedProfileCreateUuid: UUID?,
    ): UUID? {
        if (!profileService.canCreate(hyperZonePlayer.registrationName, suggestedProfileCreateUuid)) {
            return null
        }

        val createdProfile = profileService.create(hyperZonePlayer.registrationName, suggestedProfileCreateUuid)
        if (!repository.createOrUpdate(session.userName, session.xuid, createdProfile.id)) {
            throw IllegalStateException(
                "Floodgate 玩家 ${session.userName}(${session.xuid}) Profile 已创建，但模块绑定写入失败"
            )
        }
        return createdProfile.id
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

