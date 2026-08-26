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

package icu.h2l.login.auth.online.manager

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.event.auth.AuthenticationFailureEvent
import icu.h2l.api.event.auth.LoginHandleResult
import icu.h2l.api.event.auth.LoginHandleSession
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.log.info
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.player.getChannel
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import icu.h2l.login.auth.online.db.EntryTableManager
import icu.h2l.login.auth.online.iface.YggdrasilAuthFlow
import icu.h2l.login.auth.online.message.YggdrasilMessages
import icu.h2l.login.auth.online.record.PreparedYggdrasilAuth
import icu.h2l.login.auth.online.record.WaitingAreaContext
import icu.h2l.login.auth.online.record.YggdrasilAuthRequestContext
import icu.h2l.login.auth.online.record.YggdrasilAuthResult
import icu.h2l.login.auth.online.service.YggdrasilCredentialService
import icu.h2l.login.auth.online.service.YggdrasilProfileSkinDispatcher
import icu.h2l.login.auth.online.service.YggdrasilRemoteAuthService
import io.netty.channel.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * 验证管理器
 * 负责管理玩家的一层登入状态和 Yggdrasil 验证总流程。
 */
class YggdrasilAuthModule(
    private val proxy: ProxyServer,
    entryConfigManager: EntryConfigManager,
    databaseManager: HyperZoneDatabaseManager,
    entryTableManager: EntryTableManager,
    profileService: HyperZoneProfileService,
    private val playerAccessor: HyperZonePlayerAccessor
) : YggdrasilAuthFlow {
    private val credentialService = YggdrasilCredentialService(
        entryConfigManager = entryConfigManager,
        profileService = profileService,
        entryDatabaseHelper = EntryDatabaseHelper(
            databaseManager = databaseManager,
            entryTableManager = entryTableManager
        )
    )
    private val remoteAuthService = YggdrasilRemoteAuthService(
        proxy = proxy,
        entryConfigManager = entryConfigManager,
        databaseManager = databaseManager,
        entryTableManager = entryTableManager,
        profileService = profileService,
        credentialService = credentialService
    )
    private val profileSkinDispatcher = YggdrasilProfileSkinDispatcher(proxy)

    private val preparedAuthResults = ConcurrentHashMap<Channel, PreparedYggdrasilAuth>()
    private val waitingAreaPlayers = ConcurrentHashMap<Channel, WaitingAreaContext>()
    private val inFlightAuthJobs = ConcurrentHashMap<Channel, Job>()
    private val waitingAreaClaimFutures = ConcurrentHashMap<Channel, MutableList<CompletableFuture<LoginHandleResult>>>()

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun startYggdrasilAuth(
        channel: Channel,
        username: String,
        uuid: java.util.UUID,
        serverId: String,
        playerIp: String?
    ) {
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilFlow] 请求启动验证: user=$username" }
        if (preparedAuthResults.containsKey(channel)) {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "玩家 $username 已有验证结果，跳过重复验证请求" }
            return
        }

        val hyperZonePlayer = playerAccessor.getByChannel(channel)
        hyperZonePlayer.sendMessage(YggdrasilMessages.authInProgress(hyperZonePlayer))

        val authContext = YggdrasilAuthRequestContext(
            channel = channel,
            username = username,
            uuid = uuid,
            serverId = serverId,
            playerIp = playerIp
        )

        var jobToStart: Job? = null
        var skipReason: String? = null
        inFlightAuthJobs.compute(channel) { _, existingJob ->
            if (preparedAuthResults.containsKey(channel)) {
                skipReason = "玩家 $username 已有验证结果，跳过重复验证请求"
                return@compute existingJob
            }

            if (existingJob?.isActive == true) {
                skipReason = "玩家 $username 验证任务进行中，跳过重复验证请求"
                return@compute existingJob
            }

            coroutineScope.launch(start = CoroutineStart.LAZY) {
                try {
                    debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilFlow] 验证任务开始执行: user=$username" }
                    val result = remoteAuthService.authenticate(authContext) { firstBatchResult ->
                        notifyFirstBatchFailure(channel, firstBatchResult)
                    }
                    val preparedAuth = prepareAuthResult(channel, username, result)
                    preparedAuthResults[channel] = preparedAuth
                    debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                        "[YggdrasilFlow] 验证任务完成并缓存结果: user=$username, result=${preparedAuth.result.javaClass.simpleName}"
                    }
                    waitingAreaPlayers[channel]?.let { waitingAreaContext ->
                        finalizePreparedAuth(
                            player = waitingAreaContext.player,
                            session = waitingAreaContext.session,
                            preparedAuth = preparedAuth
                        )
                    } ?: run {
                        debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                            "[YggdrasilFlow] 尚未注册等待区玩家上下文，等待后续 WaitingAreaJoin: user=$username"
                        }
                    }
                } finally {
                    inFlightAuthJobs.remove(channel)
                }
            }.also { jobToStart = it }
        }

        skipReason?.let {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) { it }
            return
        }

        jobToStart?.start()
    }

    fun getAuthResult(channel: Channel): YggdrasilAuthResult? = preparedAuthResults[channel]?.result

    override fun registerWaitingAreaPlayer(player: Player, waitingAreaPlayer: HyperZonePlayer) {
        val channel = player.getChannel()
        val session = DirectHyperPlayerSession(waitingAreaPlayer)
        waitingAreaPlayers[channel] = WaitingAreaContext(player, session)
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "为玩家 ${player.username} 注册等待区玩家上下文" }

        preparedAuthResults[channel]?.let { preparedAuth ->
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                "[YggdrasilFlow] 命中已完成结果，等待当前等待区启动事件结束后回调: user=${player.username}"
            }
            dispatchCachedAuthResultLater(channel, player, session, preparedAuth)
        } ?: run {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilFlow] 验证结果尚未完成，等待异步回调: user=${player.username}" }
        }
    }

    override fun requestWaitingAreaAuth(
        player: Player,
        session: LoginHandleSession
    ): CompletableFuture<LoginHandleResult> {
        val channel = player.getChannel()
        val future = CompletableFuture<LoginHandleResult>()
        waitingAreaClaimFutures.compute(channel) { _, existing ->
            val list = existing ?: mutableListOf()
            list += future
            list
        }
        waitingAreaPlayers[channel] = WaitingAreaContext(player, session)
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "为玩家 ${player.username} 注册 claim 会话上下文" }
        preparedAuthResults[channel]?.let { preparedAuth ->
            dispatchCachedAuthResultLater(channel, player, session, preparedAuth)
        }
        future.whenComplete { _, _ ->
            waitingAreaClaimFutures.computeIfPresent(channel) { _, existing ->
                existing.remove(future)
                if (existing.isEmpty()) null else existing
            }
        }
        return future
    }

    fun getWaitingAreaPlayer(player: Player): HyperZonePlayer? {
        return (waitingAreaPlayers[player.getChannel()]?.session as? DirectHyperPlayerSession)?.player
    }

    override fun clearPlayerCacheOnDisconnect(player: Player) {
        clearTransientState(player.getChannel())
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilFlow] 玩家断连，已清理缓存状态: user=${player.username}" }
    }

    private fun prepareAuthResult(
        channel: Channel,
        username: String,
        result: YggdrasilAuthResult
    ): PreparedYggdrasilAuth {
        val hyperZonePlayer = playerAccessor.getByChannel(channel)
        if (result is YggdrasilAuthResult.Success) {
            val preparation = credentialService.prepareCredentialForSuccessfulAuth(hyperZonePlayer, result)
            if (preparation.failureReason != null) {
                val failedResult = YggdrasilAuthResult.Failed(preparation.failureReason)
                publishAuthFailure(channel, username, failedResult)
                hyperZonePlayer.sendMessage(YggdrasilMessages.profileResolveFailed(hyperZonePlayer, preparation.failureReason))
                info { "玩家 $username Yggdrasil 验证成功，但 Profile 解析失败：${preparation.failureReason}" }
                return PreparedYggdrasilAuth(
                    result = failedResult,
                    credentialToSubmit = preparation.credentialToSubmit,
                    shouldOverVerify = false
                )
            }

            info { "玩家 $username 通过 Yggdrasil 验证，Entry: ${result.entryId}" }
            hyperZonePlayer.sendMessage(YggdrasilMessages.authSucceeded(hyperZonePlayer))
            profileSkinDispatcher.fireProfileSkinPreprocessEvent(hyperZonePlayer, result)
            return PreparedYggdrasilAuth(
                result = result,
                credentialToSubmit = preparation.credentialToSubmit,
                shouldOverVerify = true
            )
        }

        val failureReason = when (result) {
            is YggdrasilAuthResult.Failed -> result.reason
            is YggdrasilAuthResult.Timeout -> "Timeout"
            is YggdrasilAuthResult.NoEntriesConfigured -> "No entries configured"
            is YggdrasilAuthResult.Success -> return PreparedYggdrasilAuth(result)
        }
        publishAuthFailure(channel, username, result)
        hyperZonePlayer.sendMessage(YggdrasilMessages.authFailed(hyperZonePlayer, failureReason))
        info { "玩家 $username Yggdrasil 验证失败" }
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "玩家 $username Yggdrasil 验证失败原因: $failureReason" }
        return PreparedYggdrasilAuth(result = result)
    }

    private fun notifyFirstBatchFailure(channel: Channel, result: YggdrasilAuthResult) {
        val handler = playerAccessor.getByChannel(channel)
        val message = when (result) {
            is YggdrasilAuthResult.Failed -> YggdrasilMessages.firstBatchFailed(handler, result.reason, result.statusCode)
            is YggdrasilAuthResult.Timeout -> YggdrasilMessages.firstBatchTimeout(handler)
            is YggdrasilAuthResult.NoEntriesConfigured -> return
            is YggdrasilAuthResult.Success -> return
        }
        handler.sendMessage(message)
    }

    private fun clearTransientStateAfterDispatch(player: Player) {
        clearTransientState(player.getChannel())
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "[YggdrasilFlow] 回调完成后已清理临时状态: user=${player.username}" }
    }

    private fun clearTransientState(channel: Channel) {
        preparedAuthResults.remove(channel)
        waitingAreaPlayers.remove(channel)
        waitingAreaClaimFutures.remove(channel)?.forEach { future ->
            if (!future.isDone && !future.isCancelled) {
                future.complete(LoginHandleResult.failed("yggdrasil flow cleared"))
            }
        }
        inFlightAuthJobs.remove(channel)?.cancel()
    }

    private fun dispatchCachedAuthResultLater(
        channel: Channel,
        player: Player,
        session: LoginHandleSession,
        preparedAuth: PreparedYggdrasilAuth
    ) {
        channel.eventLoop().execute {
            val currentContext = waitingAreaPlayers[channel]
            if (currentContext?.player !== player || currentContext.session !== session) {
                debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                    "[YggdrasilFlow] 等待区上下文已变更，跳过延后回调: user=${player.username}"
                }
                return@execute
            }

            finalizePreparedAuth(player, session, preparedAuthResults[channel] ?: preparedAuth)
        }
    }

    private fun finalizePreparedAuth(
        player: Player,
        session: LoginHandleSession,
        preparedAuth: PreparedYggdrasilAuth
    ) {
        val channel = player.getChannel()
        try {
            if (session.hasAttachedProfile()) {
                completeWaitingAreaClaims(channel, LoginHandleResult.success())
                return
            }

            if (!preparedAuth.shouldOverVerify) {
                val message = when (val result = preparedAuth.result) {
                    is YggdrasilAuthResult.Failed -> result.reason
                    is YggdrasilAuthResult.Timeout -> "Yggdrasil authentication timeout"
                    is YggdrasilAuthResult.NoEntriesConfigured -> "No Yggdrasil providers configured"
                    is YggdrasilAuthResult.Success -> "Yggdrasil verification not ready"
                }
                completeWaitingAreaClaims(channel, LoginHandleResult.failed(message))
                return
            }
            val preparedCredential = preparedAuth.credentialToSubmit
            val profileIdHint = preparedCredential?.getBoundProfileId()
            completeWaitingAreaClaims(
                channel,
                LoginHandleResult.success(
                    credential = preparedCredential,
                    profileIdHint = profileIdHint
                )
            )

            val success = preparedAuth.result as? YggdrasilAuthResult.Success
            if (success != null) {
                debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                    "玩家 ${player.username} 凭证准备完成，等待 LoginManager 完成 attach，Entry: ${success.entryId}"
                }
            }
        } catch (throwable: Throwable) {
            val message = throwable.message ?: "Yggdrasil credential submit failed"
            completeWaitingAreaClaims(channel, LoginHandleResult.failed(message))
        } finally {
            clearTransientStateAfterDispatch(player)
        }
    }

    private fun completeWaitingAreaClaims(channel: Channel, result: LoginHandleResult) {
        waitingAreaClaimFutures.remove(channel)?.forEach { future ->
            if (!future.isDone && !future.isCancelled) {
                future.complete(result)
            }
        }
    }

    private fun publishAuthFailure(channel: Channel, username: String, result: YggdrasilAuthResult) {
        val reason = when (result) {
            is YggdrasilAuthResult.Failed -> AuthenticationFailureEvent.Reason.REMOTE_REJECTED
            is YggdrasilAuthResult.Timeout -> AuthenticationFailureEvent.Reason.TIMEOUT
            is YggdrasilAuthResult.NoEntriesConfigured -> AuthenticationFailureEvent.Reason.NO_PROVIDERS
            is YggdrasilAuthResult.Success -> return
        }
        val reasonMessage = when (result) {
            is YggdrasilAuthResult.Failed -> result.reason
            is YggdrasilAuthResult.Timeout -> "Yggdrasil authentication timeout"
            is YggdrasilAuthResult.NoEntriesConfigured -> "No Yggdrasil providers configured"
            is YggdrasilAuthResult.Success -> return
        }
        val throwableSummary = (result as? YggdrasilAuthResult.Failed)?.statusCode?.let { "HTTP $it" }

        proxy.eventManager.fire(
            AuthenticationFailureEvent(
                userName = username,
                playerIp = getPlayerRemoteAddress(channel),
                authType = AuthenticationFailureEvent.AuthType.YGGDRASIL,
                reason = reason,
                reasonMessage = reasonMessage,
                providerId = null,
                throwableSummary = throwableSummary
            )
        )
    }

    private fun getPlayerRemoteAddress(channel: Channel): String {
        val remoteAddress = channel.remoteAddress()
        val hostAddress = (remoteAddress as? InetSocketAddress)?.address?.hostAddress ?: remoteAddress.toString()
        val ipv6ScopeIdx = hostAddress.indexOf('%')
        return if (ipv6ScopeIdx == -1) hostAddress else hostAddress.substring(0, ipv6ScopeIdx)
    }
}

private class DirectHyperPlayerSession(
    val player: HyperZonePlayer
) : LoginHandleSession {
    override fun originalName(): String = player.clientOriginalName

    override fun hasAttachedProfile(): Boolean = player.hasAttachedProfile()


    override fun sendMessage(message: Component) {
        player.sendMessage(message)
    }
}

