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

package icu.h2l.login.manager

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.db.Profile
import icu.h2l.api.event.area.PlayerAreaTransitionReason
import icu.h2l.api.event.auth.LoginClaim
import icu.h2l.api.event.auth.LoginHandleContext
import icu.h2l.api.event.auth.LoginHandleRequestEvent
import icu.h2l.api.event.auth.LoginHandleResult
import icu.h2l.api.event.auth.LoginHandleSession
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.getChannel
import icu.h2l.api.profile.HyperZoneCredentialFlow
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.listener.PlayerAreaLifecycleListener
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.player.VelocityHyperZonePlayer
import io.netty.channel.Channel
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LoginManager(
    private val server: ProxyServer,
    private val profileService: HyperZoneProfileService,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : HyperZoneCredentialFlow {
    private val credentialsByChannel = ConcurrentHashMap<Channel, HyperZoneCredential>()
    private val readyTransitionOwners = ConcurrentHashMap<UUID, VelocityHyperZonePlayer>()
    private val notifiedReadyPlayers = ConcurrentHashMap.newKeySet<VelocityHyperZonePlayer>()
    private val lastReadyConflictPlayerIds = ConcurrentHashMap<VelocityHyperZonePlayer, Set<UUID>>()

    override fun submitCredential(player: HyperZonePlayer, credential: HyperZoneCredential) {
        submitCredentialByChannel(resolveChannel(player), credential)
    }

    override fun replaceCredential(player: HyperZonePlayer, oldChannelId: String, credential: HyperZoneCredential) {
        val channel = resolveChannel(player)
        val existing = credentialsByChannel[channel]
            ?: throw IllegalStateException("玩家 ${player.clientOriginalName} 当前没有可替换凭证")
        if (existing.channelId != oldChannelId) {
            throw IllegalStateException("玩家 ${player.clientOriginalName} 当前凭证渠道与期望不一致: expected=$oldChannelId actual=${existing.channelId}")
        }
        credentialsByChannel[channel] = credential
    }

    override fun getSubmittedCredentials(player: HyperZonePlayer): List<HyperZoneCredential> {
        val channel = resolveChannel(player)
        return credentialsByChannel[channel]?.let(::listOf).orEmpty()
    }

    override fun clearCredentials(player: HyperZonePlayer) {
        resolveChannelOrNull(player)?.let(credentialsByChannel::remove)
    }

    fun clearCredentialsByChannel(channel: Channel) {
        credentialsByChannel.remove(channel)
    }

    override fun overVerify(player: HyperZonePlayer) {
        val attachedProfile = profileService.attachVerifiedCredentialProfile(player)
        if (attachedProfile == null) {
            player.sendMessage(HyperZoneLoginMain.getInstance().messageService.render(player, MessageKeys.Player.VERIFIED_UNBOUND))
            return
        }
        onProfileAttached(player)
    }

//    和login的区别是 这个会尝试离开等待区
    fun attachProfile(player: HyperZonePlayer, profileId: UUID): Profile? {
        val attachedProfile = profileService.attachProfile(player, profileId) ?: return null
        onProfileAttached(player)
        return attachedProfile
    }

    fun onProfileAttached(player: HyperZonePlayer) {
        val velocityPlayer = player as? VelocityHyperZonePlayer ?: return
        runCatching {
            tryLeaveWaiting(velocityPlayer)
        }.onFailure { throwable ->
            debug(HyperZoneDebugType.OUTPRE_TRACE) {
                "loginManager.onProfileAttached transition failed player=${velocityPlayer.clientOriginalName} reason=${throwable.message}"
            }
        }
    }

    override fun resetVerify(player: HyperZonePlayer) {
        clearCredentials(player)
        if (player is VelocityHyperZonePlayer) {
            resetTransientLoginState(player)
        }
    }

    private fun resetTransientLoginState(player: VelocityHyperZonePlayer) {
        notifiedReadyPlayers.remove(player)
        lastReadyConflictPlayerIds.remove(player)
    }

    private fun tryLeaveWaiting(hyperPlayer: VelocityHyperZonePlayer) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "loginManager.tryLeaveWaiting player=${hyperPlayer.clientOriginalName} attachedProfile=${hyperPlayer.hasAttachedProfile()} proxyBound=${hyperPlayer.getProxyPlayerOrNull() != null}"
        }

        if (!hyperPlayer.hasAttachedProfile()) {
            return
        }

        val player = hyperPlayer.getProxyPlayerOrNull() ?: return
        val main = HyperZoneLoginMain.getInstance()
        val attachedProfileId = profileService.getAttachedProfile(hyperPlayer)?.id ?: return

        val transitionOwner = readyTransitionOwners.putIfAbsent(attachedProfileId, hyperPlayer)
        if (transitionOwner != null && transitionOwner !== hyperPlayer) {
            notifyProfileConflict(hyperPlayer, listOf(transitionOwner))
            return
        }

        try {
            val conflictingPlayers = server.allPlayers.asSequence()
                .mapNotNull { onlinePlayer ->
                    val otherHyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(onlinePlayer) ?: return@mapNotNull null
                    if (otherHyperPlayer === hyperPlayer) {
                        return@mapNotNull null
                    }

                    if (profileService.getAttachedProfile(otherHyperPlayer)?.id != attachedProfileId) {
                        return@mapNotNull null
                    }

                    val isStillInWaitingArea = main.serverAdapter?.isPlayerInWaitingArea(onlinePlayer) == true
                    if (isStillInWaitingArea && !notifiedReadyPlayers.contains(otherHyperPlayer)) {
                        return@mapNotNull null
                    }

                    otherHyperPlayer
                }
                .toList()

            if (conflictingPlayers.isNotEmpty()) {
                notifyProfileConflict(hyperPlayer, conflictingPlayers)
                return
            }

            lastReadyConflictPlayerIds.remove(hyperPlayer)
            if (!notifiedReadyPlayers.add(hyperPlayer)) {
                return
            }

            PlayerAreaLifecycleListener.markWaitingAreaLeavePending(player, PlayerAreaTransitionReason.VERIFIED)
            main.serverAdapter?.onVerified(player)
        } finally {
            readyTransitionOwners.remove(attachedProfileId, hyperPlayer)
        }
    }

    private fun notifyProfileConflict(player: VelocityHyperZonePlayer, conflictingPlayers: List<VelocityHyperZonePlayer>) {
        val conflictPlayerIds = conflictingPlayers.asSequence()
            .filter { it !== player }
            .mapNotNull { it.clientOriginalUUID }
            .toSet()
        if (conflictPlayerIds.isEmpty()) {
            return
        }

        val previousConflictPlayerIds = lastReadyConflictPlayerIds.put(player, conflictPlayerIds)
        if (previousConflictPlayerIds == conflictPlayerIds) {
            return
        }

        val messageService = HyperZoneLoginMain.getInstance().messageService
        player.sendMessage(messageService.render(player, MessageKeys.Player.PROFILE_CONFLICT_SELF))
        conflictingPlayers.forEach { conflictingPlayer ->
            if (conflictingPlayer === player) {
                return@forEach
            }
            conflictingPlayer.sendMessage(
                messageService.render(
                    conflictingPlayer,
                    MessageKeys.Player.PROFILE_CONFLICT_OTHER
                )
            )
        }
    }

    data class ModuleFailure(
        val moduleId: String,
        val reason: String
    )

    enum class Status {
        SUCCESS,
        ALREADY_VERIFIED,
        NO_CLAIMS,
        ALL_FAILED,
        TIMEOUT,
        INTERNAL_ERROR
    }

    data class Result(
        val status: Status,
        val winnerModuleId: String? = null,
        val failures: List<ModuleFailure> = emptyList(),
        val reason: String? = null,
    )

    fun dispatch(
        proxyPlayer: Player,
        hyperZonePlayer: VelocityHyperZonePlayer,
        metadata: Map<String, String> = emptyMap(),
    ): CompletableFuture<Result> {
        if (hyperZonePlayer.hasAttachedProfile()) {
            return CompletableFuture.completedFuture(Result(status = Status.ALREADY_VERIFIED))
        }

        val output = CompletableFuture<Result>()
        val requestEvent = LoginHandleRequestEvent(proxyPlayer, hyperZonePlayer, metadata)

        server.eventManager.fire(requestEvent).whenComplete { _, eventThrowable ->
            if (eventThrowable != null) {
                output.complete(
                    Result(
                        status = Status.INTERNAL_ERROR,
                        reason = eventThrowable.message ?: "login handle request event failed",
                    )
                )
                return@whenComplete
            }

            val claims = requestEvent.claims()
            if (claims.isEmpty()) {
                output.complete(Result(status = Status.NO_CLAIMS))
                return@whenComplete
            }

            runClaims(output, proxyPlayer, hyperZonePlayer, metadata, claims)
        }

        return output
    }

    private fun runClaims(
        output: CompletableFuture<Result>,
        proxyPlayer: Player,
        hyperZonePlayer: VelocityHyperZonePlayer,
        metadata: Map<String, String>,
        claims: List<LoginClaim>,
    ) {
        val completed = AtomicBoolean(false)
        val remaining = AtomicInteger(claims.size)
        val failures = CopyOnWriteArrayList<ModuleFailure>()
        val cancelSignal = CompletableFuture<Void>()
        val runningTasks = CopyOnWriteArrayList<Pair<String, CompletableFuture<LoginHandleResult>>>()
        val playerChannel = proxyPlayer.getChannel()

        fun completeOnce(result: Result) {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            cancelSignal.complete(null)
            runningTasks.forEach { (_, future) -> future.cancel(true) }
            output.complete(result)
        }

        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute {
            if (completed.compareAndSet(false, true)) {
                cancelSignal.complete(null)
                runningTasks.forEach { (_, future) -> future.cancel(true) }
                output.complete(
                    Result(
                        status = Status.TIMEOUT,
                        failures = failures.toList(),
                        reason = "login dispatch timeout after ${timeoutMillis}ms"
                    )
                )
            }
        }

        val startedClaims = claims.map { claim ->
            val claimFuture = try {
                claim.handler.handle(
                    LoginHandleContext(
                        proxyPlayer = proxyPlayer,
                        session = VelocityLoginHandleSession(hyperZonePlayer),
                        metadata = metadata,
                        cancelSignal = cancelSignal,
                    )
                )
            } catch (throwable: Throwable) {
                CompletableFuture.completedFuture(
                    LoginHandleResult.failed(throwable.message ?: "claim threw exception")
                )
            }
            runningTasks += claim.moduleId to claimFuture
            claim to claimFuture
        }

        startedClaims.forEach { (claim, claimFuture) ->
            claimFuture.whenComplete { result, throwable ->
                playerChannel.eventLoop().execute {
                if (completed.get()) {
                    return@execute
                }

                if (throwable != null) {
                    failures += ModuleFailure(claim.moduleId, throwable.message ?: "claim future failed")
                    if (remaining.decrementAndGet() == 0) {
                        completeOnce(
                            Result(
                                status = Status.ALL_FAILED,
                                failures = failures.toList(),
                                reason = "all claims failed"
                            )
                        )
                    }
                    return@execute
                }

                val handledResult = result ?: LoginHandleResult.failed("claim completed without result")
                if (handledResult.success) {
                    if (hyperZonePlayer.hasAttachedProfile()) {
                        completeOnce(Result(status = Status.SUCCESS, winnerModuleId = claim.moduleId))
                        return@execute
                    }

                    val credential = handledResult.credential
                    if (credential == null) {
                        failures += ModuleFailure(claim.moduleId, "claim succeeded without credential")
                        if (remaining.decrementAndGet() == 0) {
                            completeOnce(
                                Result(
                                    status = Status.ALL_FAILED,
                                    failures = failures.toList(),
                                    reason = "all claims failed"
                                )
                            )
                        }
                                return@execute
                    }

                    val submitThrowable = runCatching {
                        submitCredentialByChannel(playerChannel, credential)
                    }.exceptionOrNull()
                    if (submitThrowable != null) {
                        failures += ModuleFailure(
                            claim.moduleId,
                            "manager submitCredential failed: ${submitThrowable.message ?: "unknown error"}"
                        )
                        if (remaining.decrementAndGet() == 0) {
                            completeOnce(
                                Result(
                                    status = Status.ALL_FAILED,
                                    failures = failures.toList(),
                                    reason = "all claims failed"
                                )
                            )
                        }
                                return@execute
                    }

                    val verifyThrowable = runCatching {
                        overVerify(hyperZonePlayer)
                    }.exceptionOrNull()
                    if (verifyThrowable != null) {
                        clearCredentialsByChannel(playerChannel)
                        failures += ModuleFailure(
                            claim.moduleId,
                            "manager overVerify failed: ${verifyThrowable.message ?: "unknown error"}"
                        )
                    } else if (hyperZonePlayer.hasAttachedProfile()) {
                        completeOnce(Result(status = Status.SUCCESS, winnerModuleId = claim.moduleId))
                        return@execute
                    } else {
                        clearCredentialsByChannel(playerChannel)
                        failures += ModuleFailure(claim.moduleId, "claimed success but profile was not attached")
                    }
                } else {
                    failures += ModuleFailure(claim.moduleId, handledResult.reason ?: "claim rejected")
                }

                if (remaining.decrementAndGet() == 0) {
                    completeOnce(
                        Result(
                            status = Status.ALL_FAILED,
                            failures = failures.toList(),
                            reason = "all claims failed"
                        )
                    )
                }
                }
            }
        }
    }

    private fun submitCredentialByChannel(channel: Channel, credential: HyperZoneCredential) {
        val existing = credentialsByChannel[channel]
        if (existing != null) {
            throw IllegalStateException(
                "玩家 channel=$channel 已存在凭证 ${existing.channelId}，必须先替换后再提交"
            )
        }
        credentialsByChannel[channel] = credential
    }


    private fun resolveChannel(player: HyperZonePlayer): Channel {
        return resolveChannelOrNull(player)
            ?: throw IllegalStateException("玩家 ${player.clientOriginalName} 当前未绑定 Proxy Player，无法定位 channel")
    }

    private fun resolveChannelOrNull(player: HyperZonePlayer): Channel? {
        return player.getProxyPlayerOrNull()?.getChannel()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 12_000
    }
}

private class VelocityLoginHandleSession(
    private val player: VelocityHyperZonePlayer
) : LoginHandleSession {
    override fun originalName(): String = player.clientOriginalName

    override fun hasAttachedProfile(): Boolean = player.hasAttachedProfile()


    override fun sendMessage(message: net.kyori.adventure.text.Component) {
        player.sendMessage(message)
    }
}

