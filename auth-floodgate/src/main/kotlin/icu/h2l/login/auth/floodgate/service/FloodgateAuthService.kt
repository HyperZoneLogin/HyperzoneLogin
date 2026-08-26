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
import icu.h2l.api.profile.CredentialChannelRegistryProvider
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.api.profile.HyperZoneProfileServiceProvider
import icu.h2l.login.auth.floodgate.FloodgateMessages
import icu.h2l.login.auth.floodgate.config.FloodgateAuthConfig
import icu.h2l.login.auth.floodgate.credential.FloodgateHyperZoneCredential
import icu.h2l.login.auth.floodgate.db.FloodgateAuthRepository
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import java.util.*

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
        debug(HyperZoneDebugType.FLOODGATE, message)
    }

    sealed interface VerifyResult {
        data object NotFloodgate : VerifyResult
        data object Accepted : VerifyResult
        data class Failed(val userMessage: Component) : VerifyResult
    }

    data class CompleteResult(
        val handled: Boolean,
        val passed: Boolean,
        val credential: FloodgateHyperZoneCredential? = null,
        val profileIdHint: UUID? = null,
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

        return VerifyResult.Accepted
    }

    fun complete(channel: Channel, playerName: String): CompleteResult {
        val session = sessionHolder.get(channel)
        trace(
            "complete start channel=$channel player=$playerName sessionPresent=${session != null}"
        )
        if (session == null) {
            trace("complete ignored channel=$channel player=$playerName: no floodgate session")
            return CompleteResult(handled = false, passed = false)
        }

        return try {
            val suggestedProfileCreateUuid = resolveProfileUuid(session.userUUID)
            val knownProfileId = resolveKnownProfileId(session)
                ?: run {
                    val channelAbility = CredentialChannelRegistryProvider.getOrNull()?.getChannelAbility("floodgate")
                    if (channelAbility?.canRegister == false) {
                        return CompleteResult(
                            handled = true,
                            passed = false,
                            disconnectOnFailure = false,
                            userMessage = FloodgateMessages.registrationDisabledByName(playerName)
                        )
                    }
                    createAndBindProfileIfAllowed(session, suggestedProfileCreateUuid)
                }

            if (knownProfileId == null) {
                trace("complete blocked channel=$channel player=$playerName: no known profile id")
                return CompleteResult(
                    handled = true,
                    passed = false,
                    disconnectOnFailure = false,
                    userMessage = FloodgateMessages.createBlockedByName(playerName)
                )
            }

            val credential = FloodgateHyperZoneCredential(
                repository = repository,
                trustedName = session.userName,
                trustedUuid = session.userUUID,
                xuid = session.xuid,
                suggestedProfileCreateUuid = suggestedProfileCreateUuid,
                knownProfileId = knownProfileId
            )
            sessionHolder.remove(channel)
            trace("complete success channel=$channel player=$playerName sessionCleared=true")
            CompleteResult(
                handled = true,
                passed = true,
                credential = credential,
                profileIdHint = knownProfileId
            )
        } catch (throwable: Throwable) {
            logger.warning("Floodgate 玩家 $playerName 完成认证失败: ${throwable.message}")
            trace(
                "complete failed channel=$channel player=$playerName error=${throwable.message}"
            )
            CompleteResult(
                handled = true,
                passed = false,
                disconnectOnFailure = true,
                userMessage = FloodgateMessages.completeFailedByName(playerName)
            )
        }
    }

    private fun resolveKnownProfileId(
        session: FloodgateSessionHolder.FloodgateSession,
    ): UUID? {

        val repositoryProfileId = repository.findProfileIdByXuid(session.xuid) ?: return null
        repository.updateEntryName(session.xuid, session.userName)
        return repositoryProfileId
    }

    private fun createAndBindProfileIfAllowed(
        session: FloodgateSessionHolder.FloodgateSession,
        suggestedProfileCreateUuid: UUID?,
    ): UUID? {
        // 使用凭证与 ProfileService 交互，避免裸露传递注册名与 UUID
        val candidateCredential = FloodgateHyperZoneCredential(
            repository = repository,
            trustedName = session.userName,
            trustedUuid = session.userUUID,
            xuid = session.xuid,
            suggestedProfileCreateUuid = suggestedProfileCreateUuid
        )
        if (!profileService.canCreate(candidateCredential)) {
            return null
        }

        val createdProfile = profileService.create(candidateCredential)
        if (!repository.createOrUpdate(session.userName, session.xuid, createdProfile.id)) {
            throw IllegalStateException(
                "Floodgate 玩家 ${session.userName}(${session.xuid}) Profile 已创建，但模块绑定写入失败"
            )
        }
        return createdProfile.id
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
}
