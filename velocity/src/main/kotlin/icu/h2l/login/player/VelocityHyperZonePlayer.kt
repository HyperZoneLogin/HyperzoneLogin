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

package icu.h2l.login.player

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import icu.h2l.api.db.Profile
import icu.h2l.api.event.profile.ProfileResolveEvent
import icu.h2l.api.log.error
import icu.h2l.api.log.warn
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.skin.ProfileSkinTextures
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import net.elytrium.limboapi.api.player.LimboPlayer
import net.kyori.adventure.text.Component
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `HyperZonePlayer` 的 Velocity 实现。
 *
 * 这里维护三组彼此独立但互相关联的状态：
 * 1. 连接/等待区状态：玩家是否仍在 Limbo 或后端认证等待服；
 * 2. 认证状态：子模块是否已经认可本次登录；
 * 3. Profile 状态：是否已经 attach 到正式游戏档案。
 *
 * 只有“认证通过 + 已 attach Profile”两个条件都满足时，
 * 玩家才允许离开等待区并使用正式游戏身份进入游戏区。
 */
class VelocityHyperZonePlayer(
//    最开始客户端传入的，不可信
    override var userName: String,
    override var uuid: UUID,
    isOnline: Boolean,
) : HyperZonePlayer {

    /**
     * 仅用于客户端自皮肤修复链路的“回传给客户端的名称”。
     *
     * 该值必须固定为 OpenPreLoginEvent 阶段客户端最初带入的用户名；
     * 后续即使 attach / resolve 到正式 Profile，也绝不能被重写。
     *
     * 警告：严禁将该字段作为通用身份字段或对外 API 使用。
     * 如有其他需求想读取它，请先重新审视设计，而不是直接复用。
     */
    internal val clientSendName: String = userName

    /**
     * 仅用于客户端自皮肤修复链路的“回传给客户端的 UUID”。
     *
     * 该值必须固定为 OpenPreLoginEvent 阶段客户端最初带入的 UUID；
     * 后续即使 attach / resolve 到正式 Profile，也绝不能被重写。
     *
     * 警告：严禁将该字段作为通用身份字段或对外 API 使用。
     * 如有其他需求想读取它，请先重新审视设计，而不是直接复用。
     */
    internal val clientSendUUID: UUID = uuid

    private var proxyPlayer: Player? = null

    /**
     * 当前玩家 attach 到的游戏档案ID。
     *
     * null 表示 profile 链路尚未完成，玩家仍必须停留在等待区。
     */
    @Volatile
    var profileId: UUID? = null

    /**
     * 认证链路状态，仅表示子模块是否认可本次登录。
     *
     * 该状态不代表一定能进入游戏区；还需要 profileId 已 attach。
     */
    private val isVerifiedState = AtomicBoolean(false)

    /**
     * 玩家是否已经生成过可直接发送消息的实体。
     */
    private val hasSpawned = AtomicBoolean(false)

    /**
     * 玩家进入可收消息阶段前缓存的提示消息。
     */
    private val messageQueue = ConcurrentLinkedQueue<Component>()
    private val authJoinAnnounced = AtomicBoolean(false)
    private val authHoldServerName = AtomicReference<String?>(null)
    private val postAuthTargetServerName = AtomicReference<String?>(null)
    private val onlineState = AtomicBoolean(isOnline)
    private val selfSkinAddPlayerSent = AtomicBoolean(false)
    private val latestSelfSkinTextures = AtomicReference<ProfileSkinTextures?>(null)

    /**
     * Limbo / 等待区玩家实体，仅在等待区存在。
     */
    @Volatile
    private var limboPlayer: LimboPlayer? = null

    /**
     * 等待区转发用的临时档案。
     *
     * 当玩家仍在等待区时，应优先使用该档案而不是正式游戏档案。
     */
    @Volatile
    private var temporaryGameProfile: GameProfile? = null

    private val databaseHelper = HyperZoneLoginMain.getInstance().databaseHelper

    fun update(player: Player) {
        proxyPlayer = player
        if (hasSpawned.compareAndSet(false, true)) {
            while (messageQueue.isNotEmpty()) {
                val message = messageQueue.poll() ?: continue
                proxyPlayer?.sendMessage(message)
            }
        }
    }

    fun onSpawn(player: LimboPlayer) {
        limboPlayer = player
        update(player.proxyPlayer)
        hasSpawned.set(true)

        while (messageQueue.isNotEmpty()) {
            val message = messageQueue.poll() ?: continue
            proxyPlayer?.sendMessage(message)
        }
    }

    override fun hasAttachedProfile(): Boolean {
        return profileId != null
    }

    override fun canResolveOrCreateProfile(): Boolean {
        return profileId == null
    }

    override fun resolveOrCreateProfile(userName: String?, uuid: UUID?): Profile {
        val existingProfile = getDBProfile()
        if (existingProfile != null) {
            return existingProfile
        }

        val resolvedName = userName ?: this.userName
        val remapPrefix = HyperZoneLoginMain.getRemapConfig().prefix
        val resolvedUuid = uuid ?: RemapUtils.genUUID(resolvedName, remapPrefix)
//        测试用的
//        val resolvedUuid = RemapUtils.genUUID(resolvedName, remapPrefix)

        val event = ProfileResolveEvent(
            hyperZonePlayer = this,
            trustedName = resolvedName,
            trustedUuid = resolvedUuid,
            allowCreate = true
        )
        HyperZoneLoginMain.getInstance().proxy.eventManager.fire(event).join()

        val profile = event.profile
            ?: throw IllegalStateException(event.deniedReason ?: "玩家 $resolvedName 注册失败，未能解析 Profile")

        if (!event.isResolved) {
            throw IllegalStateException(event.deniedReason ?: "玩家 $resolvedName 注册失败，Profile 解析未完成")
        }

        profileId = profile.id
        this.userName = profile.name
        this.uuid = profile.uuid
        return profile
    }

    override fun attachProfile(profileId: UUID): Profile? {
        val event = ProfileResolveEvent(
            hyperZonePlayer = this,
            profileIdHint = profileId
        )
        HyperZoneLoginMain.getInstance().proxy.eventManager.fire(event).join()

        if (!event.isResolved) {
            return null
        }

        val profile = event.profile ?: return null
        this.profileId = profile.id
        this.userName = profile.name
        this.uuid = profile.uuid
        return profile
    }

    override fun getDBProfile(): Profile? {
        val currentProfileId = profileId ?: return null
        return databaseHelper.getProfile(currentProfileId)
    }

    override fun isVerified(): Boolean {
        return isVerifiedState.get()
    }

    override fun canBind(): Boolean {
        return isVerified()
    }

    override fun overVerify() {
        if (isVerifiedState.compareAndSet(false, true)) {
            val player = proxyPlayer
            val authServer = authHoldServerName.getAndSet(null)
            val targetServer = postAuthTargetServerName.getAndSet(null)
            authJoinAnnounced.set(false)
            limboPlayer?.disconnect()

            if (player != null && !targetServer.isNullOrBlank() && !targetServer.equals(authServer, ignoreCase = true)) {
                val target = HyperZoneLoginMain.getInstance().proxy.getServer(targetServer).orElse(null)
                if (target != null) {
                    player.createConnectionRequest(target).connect().whenComplete { result, throwable ->
                        if (throwable != null) {
                            player.sendPlainMessage("§c认证完成后自动连接到目标服务器失败：${throwable.message ?: "未知错误"}")
                            return@whenComplete
                        }

                        if (result == null || !result.isSuccessful) {
                            val reason = result?.reasonComponent?.map { component ->
                                component.toString()
                            }?.orElse("未知原因") ?: "未知原因"
                            player.sendPlainMessage("§c认证完成，但自动连接到目标服务器失败：$reason")
                        }
                    }
                } else {
                    player.sendPlainMessage("§c认证完成，但目标服务器 $targetServer 不存在")
                }
            }
        }
    }

    override fun resetVerify() {
        isVerifiedState.set(false)
        authJoinAnnounced.set(false)
    }

    fun exitLimbo() {
        limboPlayer?.disconnect()
    }

    override fun sendMessage(message: Component) {
        if (hasSpawned.get()) {
            proxyPlayer?.sendMessage(message)
            return
        }

        messageQueue.offer(message)
    }

    override fun getTemporaryGameProfile(): GameProfile {
        return temporaryGameProfile
            ?: throw IllegalStateException("玩家 $userName 尚未生成临时档案，无法在等待区使用可信身份")
    }

    override fun getAttachedGameProfile(): GameProfile {
        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) {
//            不开就可以从玩家获取
            return proxyPlayer!!.gameProfile
        }

        val resolvedProfile = getDBProfile()
            ?: throw IllegalStateException("玩家 $userName 尚未 attach Profile，无法获取正式游戏档案")
        return GameProfile(
            resolvedProfile.uuid,
            resolvedProfile.name,
            emptyList()
        )
    }


    override fun setTemporaryGameProfile(profile: GameProfile?) {
        temporaryGameProfile = profile
    }

    fun isOnlinePlayer(): Boolean {
        return onlineState.get()
    }

    fun setOnlinePlayer(isOnline: Boolean) {
        onlineState.set(isOnline)
    }

    fun beginBackendAuthHold(authServerName: String, targetServerName: String?) {
        authHoldServerName.set(authServerName)
        postAuthTargetServerName.set(targetServerName?.takeUnless { it.isBlank() })
        authJoinAnnounced.set(false)
    }

    fun isInBackendAuthHold(): Boolean {
        return isInWaitingArea() && !authHoldServerName.get().isNullOrBlank()
    }

    /**
     * 判断玩家当前是否真的位于“后端等待区服务器”上。
     *
     * 注意：这里故意不能复用 `isInBackendAuthHold()`，也不能依赖 `isInWaitingArea()`。
     * 原因是玩家在等待区服务器完成登录后，`overVerify()` 会清掉 hold 状态，
     * 同时 `isInWaitingArea()` 也可能变为 false；但这类玩家之后仍然可能主动重新进入该服务器，
     * 用于登出、改密、换绑等后续操作。
     *
     * 所以“命令是否允许在代理兜底层执行”的判定，必须基于玩家当前所在服务器，
     * 而不是一次性的认证 hold 标记，否则未来很容易再次误收紧这里的逻辑。
     */
    fun isOnBackendAuthServer(): Boolean {
        val authServerName = HyperZoneLoginMain.getBackendServerConfig().fallbackAuthServer.trim()
        if (authServerName.isBlank()) {
            return false
        }

        val currentServerName = proxyPlayer?.currentServer
            ?.map { it.server.serverInfo.name }
            ?.orElse(null)
            ?: return false

        return currentServerName.equals(authServerName, ignoreCase = true)
    }

    fun getBackendAuthHoldServerName(): String? {
        return authHoldServerName.get()
    }

    fun getPostAuthTargetServerName(): String? {
        return postAuthTargetServerName.get()
    }

    fun rememberPostAuthTarget(serverName: String?) {
        val resolved = serverName?.takeUnless { it.isBlank() } ?: return
        postAuthTargetServerName.set(resolved)
    }

    fun markBackendAuthJoinHandled(serverName: String): Boolean {
        val holdServer = authHoldServerName.get() ?: return false
        if (!holdServer.equals(serverName, ignoreCase = true)) {
            return false
        }
        return authJoinAnnounced.compareAndSet(false, true)
    }

    fun clearBackendAuthHold() {
        authHoldServerName.set(null)
        postAuthTargetServerName.set(null)
        authJoinAnnounced.set(false)
    }

    /**
     * 仅供 `ProfileSkinPreprocessEvent` 监听器调用：
     * 记录最新的 self 皮肤资料，并在连接已经可写时直接补发一次 self `ADD_PLAYER`。
     */
    internal fun sendSelfAddPlayerFromPreprocess(textures: ProfileSkinTextures) {
        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) {
            return
        }
        if (textures.value.isBlank()) {
            return
        }

        latestSelfSkinTextures.set(textures)
        if (selfSkinAddPlayerSent.get()) {
            return
        }

        sendSelfAddPlayer(textures, forceReplay = false, failureLabel = "Preprocess")
    }

    /**
     * `ClientboundFinishConfigurationPacket` 之后客户端会重建 `ClientPacketListener`，
     * 原先 self `PlayerInfo` 可能随之丢失。
     *
     * 因此在 Velocity 的 `PlayerFinishConfigurationEvent` 到来后，
     * 这里会基于最近一次可用的 self 皮肤资料重新补发一次 self `ADD_PLAYER`。
     */
    internal fun replaySelfAddPlayerAfterConfigurationFinish() {
        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) {
            return
        }

        val textures = latestSelfSkinTextures.get() ?: return
        if (textures.value.isBlank()) {
            return
        }

        sendSelfAddPlayer(textures, forceReplay = true, failureLabel = "Post-configuration replay")
    }

    private fun sendSelfAddPlayer(
        textures: ProfileSkinTextures,
        forceReplay: Boolean,
        failureLabel: String
    ) {
        if (!forceReplay && selfSkinAddPlayerSent.get()) {
            return
        }

        val connectedPlayer = proxyPlayer as? ConnectedPlayer ?: return
        if (!connectedPlayer.isActive || connectedPlayer.connection.isClosed) {
            return
        }

        val property = textures.toPropertyOrNull() ?: run {
            warn {
                "[ProfileSkinFlow] $failureLabel self ADD_PLAYER skipped due to incomplete textures: player=$userName, valueLength=${textures.value.length}, signed=${textures.isSigned}"
            }
            return
        }

        if (!forceReplay && !selfSkinAddPlayerSent.compareAndSet(false, true)) {
            return
        }

        val replayProfile = GameProfile(
            clientSendUUID,
            clientSendName,
            listOf(property)
        )

        connectedPlayer.connection.eventLoop().execute {
            try {
                if (!connectedPlayer.isActive || connectedPlayer.connection.isClosed) {
                    if (!forceReplay) {
                        selfSkinAddPlayerSent.set(false)
                    }
                    return@execute
                }

                SelfPlayerInfoSkinSender.sendAddPlayer(connectedPlayer, replayProfile)
                selfSkinAddPlayerSent.set(true)
            } catch (throwable: Throwable) {
                if (!forceReplay) {
                    selfSkinAddPlayerSent.set(false)
                }
                error(throwable) {
                    "$failureLabel self ADD_PLAYER failed for player=$userName: ${throwable.message}"
                }
            }
        }
    }
}

