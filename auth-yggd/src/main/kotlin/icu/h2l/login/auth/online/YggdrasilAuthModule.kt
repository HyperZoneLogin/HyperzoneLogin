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

package icu.h2l.login.auth.online

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.VelocityServer
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.event.auth.AuthenticationFailureEvent
import icu.h2l.api.event.profile.ProfileSkinPreprocessEvent
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.api.log.info
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.api.profile.skin.ProfileSkinModel
import icu.h2l.api.profile.skin.ProfileSkinSource
import icu.h2l.api.profile.skin.ProfileSkinTextures
import icu.h2l.login.auth.online.config.entry.EntryConfig
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import icu.h2l.login.auth.online.db.EntryTableManager
import icu.h2l.login.auth.online.manager.EntryConfigManager
import icu.h2l.login.auth.online.req.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.net.http.HttpClient
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 验证管理器
 * 负责管理玩家的一层登入状态和Yggdrasil验证逻辑
 */
class YggdrasilAuthModule(
    private val proxy: ProxyServer,
    private val entryConfigManager: EntryConfigManager,
    private val databaseManager: HyperZoneDatabaseManager,
    private val entryTableManager: EntryTableManager,
    private val playerAccessor: HyperZonePlayerAccessor,
    private val profileService: HyperZoneProfileService
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val gson = VelocityServer.GENERAL_GSON

    /**
     * 存储验证结果
        * Key: 玩家连接
     * Value: 验证结果
     */
        private val authResults = ConcurrentHashMap<Player, YggdrasilAuthResult>()

    /**
     * 存储LimboAuthSessionHandler实例
        * Key: 玩家连接
     * Value: LimboAuthSessionHandler实例
     */
        private val limboHandlers = ConcurrentHashMap<Player, HyperZonePlayer>()

    private val entryDatabaseHelper = EntryDatabaseHelper(
        databaseManager = databaseManager,
        entryTableManager = entryTableManager
    )

    /**
     * 存储正在进行中的验证任务
        * Key: 玩家连接
     * Value: 验证任务
     */
        private val inFlightAuthJobs = ConcurrentHashMap<Player, Job>()

    private val authLaunchLock = Any()

    /**
     * 协程作用域
     */
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 启动异步Yggdrasil验证（不阻塞）
     * 
     * @param player 玩家连接
     * @param username 玩家用户名
     * @param uuid 玩家UUID
     * @param serverId 服务器ID
     * @param playerIp 玩家IP
     */
    fun startYggdrasilAuth(
        player: Player,
        username: String,
        uuid: UUID,
        serverId: String,
        playerIp: String? = null
    ) {
        debug { "[YggdrasilFlow] 请求启动验证: user=$username" }
        synchronized(authLaunchLock) {
            if (authResults.containsKey(player)) {
                debug { "玩家 $username 已有验证结果，跳过重复验证请求" }
                return
            }

            val runningJob = inFlightAuthJobs[player]
            if (runningJob?.isActive == true) {
                debug { "玩家 $username 验证任务进行中，跳过重复验证请求" }
                return
            }

            val job = coroutineScope.launch {
                try {
                    debug { "[YggdrasilFlow] 验证任务开始执行: user=$username" }
                    val result = performYggdrasilAuth(player, username, uuid, serverId, playerIp)
                    authResults[player] = result
                    debug { "[YggdrasilFlow] 验证任务完成并缓存结果: user=$username, result=${result.javaClass.simpleName}" }
                    limboHandlers[player]?.let { handler ->
                        dispatchAuthResultToHandler(player, username, handler, result)
                    } ?: run {
                        debug { "[YggdrasilFlow] 尚未注册 Limbo handler，等待后续 LimboSpawnEvent: user=$username" }
                    }
                } finally {
                    inFlightAuthJobs.remove(player)
                }
            }

            inFlightAuthJobs[player] = job
        }
    }

    /**
     * 获取玩家的验证结果
     * 
     * @param player 玩家连接
     * @return 验证结果，如果还未验证完成则返回null
     */
    fun getAuthResult(player: Player): YggdrasilAuthResult? {
        return authResults[player]
    }

    /**
     * 注册玩家的LimboAuthSessionHandler实例
     * 应该在玩家开始验证时就调用此方法
     * 
     * @param player 玩家连接
     * @param handler LimboAuthSessionHandler实例
     */
    fun registerLimboHandler(player: Player, handler: HyperZonePlayer) {
        limboHandlers[player] = handler
        debug { "为玩家 ${player.username} 注册 LimboAuthSessionHandler" }

        authResults[player]?.let { result ->
            debug { "[YggdrasilFlow] 命中已完成结果，立即回调: user=${player.username}" }
            val displayName = (result as? YggdrasilAuthResult.Success)?.profile?.name ?: "unknown"
            dispatchAuthResultToHandler(player, displayName, handler, result)
        } ?: run {
            debug { "[YggdrasilFlow] 验证结果尚未完成，等待异步回调: user=${player.username}" }
        }
    }

    /**
     * 获取玩家的LimboAuthSessionHandler实例
     * 
     * @param player 玩家连接
     * @return LimboAuthSessionHandler实例，如果未注册则返回null
     */
    fun getLimboHandler(player: Player): HyperZonePlayer? {
        return limboHandlers[player]
    }


    private fun dispatchAuthResultToHandler(
        player: Player,
        username: String,
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult
    ) {
        try {
            if (result is YggdrasilAuthResult.Success) {
                val profileResolveError = ensureCredentialForSuccessfulAuth(handler, result)
                if (profileResolveError != null) {
                    val failedResult = YggdrasilAuthResult.Failed(profileResolveError)
                    publishAuthFailure(player, username, failedResult)
                    handler.sendMessage(YggdrasilMessages.profileResolveFailed(handler, profileResolveError))
                    info { "玩家 $username Yggdrasil 验证成功，但 Profile 解析失败：$profileResolveError" }
                    return
                }

                info { "玩家 $username 通过 Yggdrasil 验证，Entry: ${result.entryId}" }
                fireProfileSkinPreprocessEvent(handler, result)
                if (handler.isInWaitingArea()) {
                    runCatching {
                        handler.overVerify()
                    }.onFailure { throwable ->
                        val message = throwable.message ?: "认证成功，但 Profile 绑定失败"
                        val failedResult = YggdrasilAuthResult.Failed(message)
                        publishAuthFailure(player, username, failedResult)
                        handler.sendMessage(YggdrasilMessages.verifyCompleteFailed(handler, message))
                        info { "玩家 $username Yggdrasil 验证成功，但完成验证失败：$message" }
                        return
                    }
                    debug { "玩家 $username 调用验证完成接口成功，Entry: ${result.entryId}"  }
                }
                return
            }

            val failureReason = when (result) {
                is YggdrasilAuthResult.Failed -> result.reason
                is YggdrasilAuthResult.Timeout -> "Timeout"
                is YggdrasilAuthResult.NoEntriesConfigured -> "No entries configured"
            }
            publishAuthFailure(player, username, result)
            handler.sendMessage(YggdrasilMessages.authFailed(handler, username))
            info { "玩家 $username Yggdrasil 验证失败" }
            debug { "玩家 $username Yggdrasil 验证失败原因: $failureReason" }
        } finally {
            clearTransientStateAfterDispatch(player)
        }
    }

    private fun ensureCredentialForSuccessfulAuth(
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult.Success
    ): String? {
        if (profileService.getAttachedProfile(handler) != null) {
            return null
        }

        val existingBoundProfileId = findBoundProfileIdByAuthenticatedEntry(result)
        if (existingBoundProfileId != null) {
            handler.submitCredential(
                yggdrasilCredential(
                    entryId = result.entryId,
                    authenticatedName = result.profile.name,
                    authenticatedUuid = result.profile.id,
                    knownProfileId = existingBoundProfileId
                )
            )
            return null
        }

        if (profileService.canResolveOrCreateProfile(result.profile.name, result.profile.id)) {
            val resolvedProfile = try {
                profileService.resolveOrCreateProfile(handler, result.profile.name, result.profile.id)
            } catch (throwable: IllegalStateException) {
                return throwable.message ?: "创建 Profile 失败"
            }

            val bound = entryDatabaseHelper.createEntry(
                entryId = result.entryId,
                name = result.profile.name,
                uuid = result.profile.id,
                pid = resolvedProfile.id
            )
            if (bound) {
                handler.submitCredential(
                    yggdrasilCredential(
                        entryId = result.entryId,
                        authenticatedName = result.profile.name,
                        authenticatedUuid = result.profile.id,
                        knownProfileId = resolvedProfile.id
                    )
                )
                return null
            }
        }

        handler.submitCredential(
            yggdrasilCredential(
                entryId = result.entryId,
                authenticatedName = result.profile.name,
                authenticatedUuid = result.profile.id
            )
        )
        return null
    }

    private fun clearTransientStateAfterDispatch(player: Player) {
        clearTransientState(player)
        debug { "[YggdrasilFlow] 回调完成后已清理临时状态: user=${player.username}" }
    }

    private fun clearTransientState(player: Player) {
        authResults.remove(player)
        limboHandlers.remove(player)
        inFlightAuthJobs.remove(player)?.cancel()
    }

    private fun fireProfileSkinPreprocessEvent(
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult.Success
    ) {
        val event = ProfileSkinPreprocessEvent(
            hyperZonePlayer = handler,
            authenticatedProfile = result.profile,
            entryId = result.entryId,
            serverUrl = result.serverUrl
        )
        event.textures = extractTextures(result.profile)
        event.source = extractSkinSource(event.textures)

        debug {
            "[ProfileSkinFlow] preprocess dispatch start: clientOriginal=${handler.clientOriginalName}, entry=${result.entryId}, server=${result.serverUrl}, authenticatedProfile=${describeProfile(result.profile)}, eventTextures=${describeTextures(event.textures)}, eventSource=${describeSource(event.source)}"
        }

        runCatching {
            proxy.eventManager.fire(event).join()
        }.onSuccess {
            debug {
                "[ProfileSkinFlow] preprocess dispatch completed: clientOriginal=${handler.clientOriginalName}, entry=${result.entryId}, resultingTextures=${describeTextures(event.textures)}, resultingSource=${describeSource(event.source)}"
            }
        }.onFailure { throwable ->
            error(throwable) { "Profile skin preprocess event failed: ${throwable.message}" }
        }
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

    private fun describeProfile(profile: GameProfile?): String {
        if (profile == null) {
            return "null"
        }
        return "id=${profile.id}, name=${profile.name}, properties=${profile.properties.size}, textures=${describeTextures(profile.properties.firstOrNull { it.name.equals("textures", ignoreCase = true) }?.let { ProfileSkinTextures(it.value, it.signature) })}"
    }

    private fun describeTextures(textures: ProfileSkinTextures?): String {
        if (textures == null) {
            return "none"
        }
        return "present(valueLength=${textures.value.length}, signed=${textures.isSigned})"
    }

    private fun describeSource(source: ProfileSkinSource?): String {
        if (source == null) {
            return "none"
        }
        return "url=${source.skinUrl}, model=${source.model}"
    }

    fun clearPlayerCacheOnDisconnect(player: Player) {
        clearTransientState(player)
        debug { "[YggdrasilFlow] 玩家断连，已清理缓存状态: user=${player.username}" }
    }

    /**（内部方法，由startYggdrasilAuth调用）
     * 
     * 验证逻辑分为两个批次：
     * 1. 第一批次：查询数据库中是否有该玩家的记录（通过UUID或用户名），
    *    如果有，则向对应的Entry验证入口发起验证请求
     * 2. 第二批次：如果第一批次没有找到或验证失败，
    *    则向所有配置的Yggdrasil Entry发起验证请求
     * 
     * @param username 玩家用户名
     * @param uuid 玩家UUID
     * @param serverId 服务器ID
     * @param playerIp 玩家IP
     * @return 验证结果
     */
    private fun performYggdrasilAuth(
        player: Player,
        username: String,
        uuid: UUID,
        serverId: String,
        playerIp: String? = null
    ): YggdrasilAuthResult = runBlocking {
        debug { "开始对玩家 $username (UUID: $uuid) 进行Yggdrasil验证" }

        // 第一批次：仅根据连接早期拿到的客户端标识筛选候选 Entry，真正确认绑定仍以后续可信认证结果为准。
        val knownEntries = findCandidateEntriesByClientIdentity(username, uuid)

        if (knownEntries.isNotEmpty()) {
            debug { "玩家 $username 在数据库中找到 ${knownEntries.size} 个Entry记录" }

            // 构建第一批次的验证请求
            val firstBatchRequests = buildAuthRequests(knownEntries)

            if (firstBatchRequests.isNotEmpty()) {
                val firstBatchResult = executeAuthRequests(
                    username, serverId, playerIp, firstBatchRequests, "第一批次"
                )

                if (firstBatchResult.isSuccess) {
                    val firstBatchValidation = validateFirstBatchProfile(username, uuid, firstBatchResult)
                    if (firstBatchValidation != null) {
                        return@runBlocking firstBatchValidation
                    }
                    return@runBlocking firstBatchResult
                } else {
                    notifyFirstBatchFailure(player, firstBatchResult)
                }
            }
        }

        debug { "第一批次验证未通过，开始第二批次（所有Yggdrasil Entry）" }

        // 第二批次：立即向所有Yggdrasil Entry发起请求
        val secondBatchContext = SecondBatchContext(username, uuid, serverId, playerIp)
        runSecondBatchAuth(secondBatchContext)
    }

    private fun validateFirstBatchProfile(
        username: String,
        uuid: UUID,
        result: YggdrasilAuthResult
    ): YggdrasilAuthResult? {
        val success = result as? YggdrasilAuthResult.Success ?: return null

        val entryProfileId = findBoundProfileIdByAuthenticatedEntry(success)
            ?: return YggdrasilAuthResult.Failed("第一批次验证失败：未获取到 Entry Profile")

        if (profileService.getProfile(entryProfileId) == null) {
            return YggdrasilAuthResult.Failed("第一批次验证失败：无法找到玩家 Profile")
        }

        debug {
            "[YggdrasilFlow] 第一批次已确认可信 Profile: user=$username requestUuid=$uuid authenticatedName=${success.profile.name} authenticatedUuid=${success.profile.id} pid=$entryProfileId"
        }
        return null
    }

    private fun notifyFirstBatchFailure(player: Player, result: YggdrasilAuthResult) {
        val handler = limboHandlers[player] ?: return

        val message = when (result) {
            is YggdrasilAuthResult.Failed -> YggdrasilMessages.firstBatchFailed(handler, result.reason, result.statusCode)
            is YggdrasilAuthResult.Timeout -> YggdrasilMessages.firstBatchTimeout(handler)
            else -> return
        }

        handler.sendMessage(message)
    }

    private suspend fun runSecondBatchAuth(
        context: SecondBatchContext
    ): YggdrasilAuthResult {
        val allYggdrasilEntries = getAllYggdrasilEntries()
        val secondBatchRequests = buildAuthRequests(allYggdrasilEntries)

        if (secondBatchRequests.isEmpty()) {
            return YggdrasilAuthResult.NoEntriesConfigured
        }

        val secondBatchResult = executeAuthRequests(
            context.username,
            context.serverId,
            context.playerIp,
            secondBatchRequests,
            "第二批次"
        )

        return secondBatchResult
    }

    /**
     * 使用远端认证成功后返回的可信身份，确认 Entry 绑定到的 Profile。
     */
    private fun findBoundProfileIdByAuthenticatedEntry(success: YggdrasilAuthResult.Success): UUID? {
        val profileId = entryDatabaseHelper.findEntryByUuid(
            entryId = success.entryId,
            uuid = success.profile.id
        ) ?: return null

        entryDatabaseHelper.updateEntryName(
            entryId = success.entryId,
            uuid = success.profile.id,
            newName = success.profile.name
        )

        return profileId
    }

    private fun findCandidateEntriesByClientIdentity(username: String, uuid: UUID): List<String> {
        val foundEntries = mutableSetOf<String>()

        // 获取所有已注册的Entry表
        val allEntries = entryConfigManager.getAllConfigs()

        databaseManager.executeTransaction {
            for ((_, entryConfig) in allEntries) {
                val entryTable = entryTableManager.getEntryTable(entryConfig.id.lowercase())

                if (entryTable != null) {
                    // 这里只做候选 Entry 发现，属于弱匹配；真正的绑定确认必须在远端认证成功后
                    // 使用可信返回的 uuid 反查 Entry -> profileId，并同步最新 name。
                    val hasRecord =
                        entryTable.selectAll().where { (entryTable.name eq username) or (entryTable.uuid eq uuid) }
                            .count() > 0

                    if (hasRecord) {
                        foundEntries.add(entryConfig.id)
                        debug { "在Entry表 ${entryConfig.id} 中找到玩家 $username 的记录" }
                    }
                }
            }
        }

        return foundEntries.toList()
    }

    /**
     * 获取所有配置的Yggdrasil Entry
     * 
     * @return Entry配置列表
     */
    private fun getAllYggdrasilEntries(): List<EntryConfig> {
        return entryConfigManager.getAllConfigs().values.toList()
    }

    /**
     * 构建验证请求列表
     * 
     * @param entries Entry ID列表或Entry配置列表
     * @return AuthenticationRequest列表
     */
    private fun buildAuthRequests(entries: List<Any>): List<Pair<String, AuthenticationRequest>> {
        val requests = mutableListOf<Pair<String, AuthenticationRequest>>()

        for (entry in entries) {
            val entryConfig = when (entry) {
                is String -> entryConfigManager.getConfigById(entry)
                is EntryConfig -> entry
                else -> null
            } ?: continue

            // 构建AuthServerConfig
            val authServerConfig = AuthServerConfig(
                url = entryConfig.yggdrasil.url,
                name = entryConfig.name,
                connectTimeout = Duration.ofSeconds(5),
                readTimeout = Duration.ofSeconds(10)
            )

            // 创建MojangStyleAuthRequest
            val authRequest = MojangStyleAuthRequest(
                config = authServerConfig,
                httpClient = httpClient,
                gson = gson,
                preventProxy = proxy.configuration.shouldPreventClientProxyConnections()
            )

            requests.add(Pair(entryConfig.id, authRequest))
        }

        return requests
    }

    /**
     * 执行验证请求（并发）
     * 
     * @param username 玩家用户名
     * @param serverId 服务器ID
     * @param playerIp 玩家IP
     * @param requests 验证请求列表
     * @param batchName 批次名称（用于日志）
     * @return 验证结果
     */
    private suspend fun executeAuthRequests(
        username: String,
        serverId: String,
        playerIp: String?,
        requests: List<Pair<String, AuthenticationRequest>>,
        batchName: String
    ): YggdrasilAuthResult {
        debug { "$batchName: 开始并发验证，共 ${requests.size} 个 Entry" }

        // 创建并发验证管理器
        val authManager = ConcurrentAuthenticationManager(
            authRequests = requests.map { AuthenticationRequestEntry(it.first, it.second) },
            globalTimeout = Duration.ofSeconds(30)
        )

        // 执行并发验证
        return when (val result = authManager.authenticate(username, serverId, playerIp)) {
            is AuthenticationResult.Success -> {
                YggdrasilAuthResult.Success(
                    profile = result.profile,
                    entryId = result.entryId ?: "unknown",
                    serverUrl = result.serverUrl
                )
            }

            is AuthenticationResult.Failure -> {
                YggdrasilAuthResult.Failed(
                    reason = result.reason,
                    statusCode = result.statusCode
                )
            }

            is AuthenticationResult.Timeout -> {
                YggdrasilAuthResult.Timeout
            }
        }
    }

    private fun publishAuthFailure(player: Player, username: String, result: YggdrasilAuthResult) {
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
        val providerId: String? = null
        val throwableSummary = (result as? YggdrasilAuthResult.Failed)?.statusCode?.let { "HTTP $it" }

        proxy.eventManager.fire(
            AuthenticationFailureEvent(
                userName = username,
                playerIp = getPlayerRemoteAddress(player),
                authType = AuthenticationFailureEvent.AuthType.YGGDRASIL,
                reason = reason,
                reasonMessage = reasonMessage,
                providerId = providerId,
                throwableSummary = throwableSummary
            )
        )
    }

    private fun getPlayerRemoteAddress(player: Player): String {
        val hostAddress = player.remoteAddress.address.hostAddress
        val ipv6ScopeIdx = hostAddress.indexOf('%')
        return if (ipv6ScopeIdx == -1) {
            hostAddress
        } else {
            hostAddress.substring(0, ipv6ScopeIdx)
        }
    }

    private fun yggdrasilCredential(
        entryId: String,
        authenticatedName: String,
        authenticatedUuid: UUID,
        knownProfileId: UUID? = null
    ): YggdrasilHyperZoneCredential {
        return YggdrasilHyperZoneCredential(
            entryDatabaseHelper = entryDatabaseHelper,
            entryId = entryId,
            authenticatedName = authenticatedName,
            authenticatedUUID = authenticatedUuid,
            knownProfileId = knownProfileId
        )
    }
}

/**
 * Yggdrasil验证结果
 */
sealed class YggdrasilAuthResult {
    /**
     * 验证成功
     */
    data class Success(
        val profile: GameProfile,
        val entryId: String,
        val serverUrl: String
    ) : YggdrasilAuthResult()

    /**
     * 验证失败
     */
    data class Failed(
        val reason: String,
        val statusCode: Int? = null
    ) : YggdrasilAuthResult()

    /**
     * 验证超时
     */
    object Timeout : YggdrasilAuthResult()

    /**
    * 没有配置的Entry
     */
    object NoEntriesConfigured : YggdrasilAuthResult()

    val isSuccess: Boolean
        get() = this is Success
}

private data class SecondBatchContext(
    val username: String,
    val uuid: UUID,
    val serverId: String,
    val playerIp: String?
)
