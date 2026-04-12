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
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import net.kyori.adventure.text.Component
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `HyperZonePlayer` 的 Velocity 实现。
 *
 * 这里维护三组彼此独立但互相关联的状态：
 * 1. 连接/等待区状态：玩家是否仍在 Limbo 或后端认证等待服；
 * 2. 认证状态：子模块是否已经认可本次登录；
 * 3. 凭证状态：子模块已经向当前会话提交了哪些可信凭证。
 *
 * 核心会在 overVerify() 时统一根据凭证 attach 正式 Profile。
 * 只有“认证通过 + 已 attach Profile”两个条件都满足时，
 * 玩家才允许离开等待区并使用正式游戏身份进入游戏区。
 */
class VelocityHyperZonePlayer(
//    最开始客户端传入的，不可信；仅用于调试、客户端回放与第一次拟定默认生成名
    override val clientOriginalName: String,
    override val clientOriginalUUID: UUID,
    override val isOnlinePlayer: Boolean,
) : HyperZonePlayer {

    companion object {
        private val readyTransitionOwners = ConcurrentHashMap<UUID, VelocityHyperZonePlayer>()
    }


    private var proxyPlayer: Player? = null

    /**
     * 认证链路状态，仅表示子模块是否认可本次登录。
     *
     * 该状态不代表一定能进入游戏区；还需要核心层已根据凭证 attach Profile。
     */
    private val isVerifiedState = AtomicBoolean(false)
    private val hasNotifiedReadyState = AtomicBoolean(false)
    private val submittedCredentials = CopyOnWriteArrayList<HyperZoneCredential>()

    /**
     * 玩家是否已经生成过可直接发送消息的实体。
     */
    private val hasSpawned = AtomicBoolean(false)

    /**
     * 玩家进入可收消息阶段前缓存的提示消息。
     */
    private val messageQueue = ConcurrentLinkedQueue<Component>()
    private val lastReadyConflictPlayerIds = AtomicReference<Set<UUID>>(emptySet())

    /**
     * 等待区转发用的临时档案。
     *
     * 当玩家仍在等待区时，应优先使用该档案而不是正式游戏档案。
     */
    @Volatile
    private var temporaryGameProfile: GameProfile? = null

    fun update(player: Player) {
        proxyPlayer = player
        if (hasSpawned.compareAndSet(false, true)) {
            while (messageQueue.isNotEmpty()) {
                val message = messageQueue.poll() ?: continue
                proxyPlayer?.sendMessage(message)
            }
        }
        tryNotifyReady()
    }

    override fun hasAttachedProfile(): Boolean {
        return HyperZoneLoginMain.getInstance().profileService.hasAttachedProfile(this)
    }

    override fun submitCredential(credential: HyperZoneCredential) {
        submittedCredentials.removeIf {
            it.channelId == credential.channelId && it.credentialId == credential.credentialId
        }
        submittedCredentials += credential
    }

    override fun getSubmittedCredentials(): List<HyperZoneCredential> {
        return submittedCredentials.toList()
    }

    override fun isVerified(): Boolean {
        return isVerifiedState.get()
    }

    override fun canBind(): Boolean {
        return isVerified()
    }

    override fun overVerify() {
        HyperZoneLoginMain.getInstance().profileService.attachVerifiedCredentialProfile(this)
        if (!isVerifiedState.compareAndSet(false, true)) {
            return
        }

        tryNotifyReady()
        if (!hasAttachedProfile()) {
            sendMessage(Component.text("§e认证已通过，但尚未绑定档案。请使用 /bindcode use <绑定码> 完成绑定。"))
        }
    }

    override fun resetVerify() {
        isVerifiedState.set(false)
        hasNotifiedReadyState.set(false)
        submittedCredentials.clear()
        lastReadyConflictPlayerIds.set(emptySet())
    }

    override fun sendMessage(message: Component) {
        if (hasSpawned.get()) {
            proxyPlayer?.sendMessage(message)
            return
        }

        messageQueue.offer(message)
    }

    override fun getProxyPlayerOrNull(): Player? {
        return proxyPlayer
    }

    override fun getTemporaryGameProfile(): GameProfile {
        return temporaryGameProfile
            ?: throw IllegalStateException("玩家 $clientOriginalName 尚未生成临时档案，无法在等待区使用可信身份")
    }

    override fun getAttachedGameProfile(): GameProfile {
        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) {
//            不开就可以从玩家获取
            return proxyPlayer!!.gameProfile
        }

        val resolvedProfile = HyperZoneLoginMain.getInstance().profileService.getAttachedProfile(this)
            ?: throw IllegalStateException("玩家 $clientOriginalName 尚未 attach Profile，无法获取正式游戏档案")
        return GameProfile(
            resolvedProfile.uuid,
            resolvedProfile.name,
            emptyList()
        )
    }


    override fun setTemporaryGameProfile(profile: GameProfile?) {
        temporaryGameProfile = profile
    }

    internal fun onAttachedProfileAvailable() {
        tryNotifyReady()
    }

    private fun tryNotifyReady() {
        if (!isVerifiedState.get() || !hasAttachedProfile()) {
            return
        }

        val player = proxyPlayer ?: return
        val main = HyperZoneLoginMain.getInstance()
        val profileService = main.profileService
        val attachedProfileId = profileService.getAttachedProfileId(this) ?: return

        val transitionOwner = readyTransitionOwners.putIfAbsent(attachedProfileId, this)
        if (transitionOwner != null && transitionOwner !== this) {
            notifyProfileConflict(listOf(transitionOwner))
            return
        }

        try {
            val conflictingPlayers = main.proxy.allPlayers.asSequence()
                .mapNotNull { onlinePlayer ->
                    val otherHyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(onlinePlayer) ?: return@mapNotNull null
                    if (otherHyperPlayer === this) {
                        return@mapNotNull null
                    }

                    if (profileService.getAttachedProfileId(otherHyperPlayer) != attachedProfileId) {
                        return@mapNotNull null
                    }

                    val isStillInWaitingArea = main.serverAdapter?.isPlayerInWaitingArea(onlinePlayer) == true
                    if (isStillInWaitingArea && !otherHyperPlayer.hasNotifiedReadyState.get()) {
                        return@mapNotNull null
                    }

                    otherHyperPlayer
                }
                .toList()

            if (conflictingPlayers.isNotEmpty()) {
                notifyProfileConflict(conflictingPlayers)
                return
            }

            lastReadyConflictPlayerIds.set(emptySet())
            if (!hasNotifiedReadyState.compareAndSet(false, true)) {
                return
            }

            main.serverAdapter?.onVerified(player)
        } finally {
            readyTransitionOwners.remove(attachedProfileId, this)
        }
    }

    private fun notifyProfileConflict(conflictingPlayers: List<VelocityHyperZonePlayer>) {
        val conflictPlayerIds = conflictingPlayers.asSequence()
            .filter { it !== this }
            .map { it.clientOriginalUUID }
            .toSet()
        if (conflictPlayerIds.isEmpty()) {
            return
        }

        val previousConflictPlayerIds = lastReadyConflictPlayerIds.getAndSet(conflictPlayerIds)
        if (previousConflictPlayerIds == conflictPlayerIds) {
            return
        }

        sendMessage(Component.text("§c当前已有同档案玩家正在游玩或正在进入游戏区，已阻止你进入游戏区。"))
        conflictingPlayers.forEach { conflictingPlayer ->
            if (conflictingPlayer === this) {
                return@forEach
            }
            conflictingPlayer.sendMessage(Component.text("§e检测到同档案再次尝试登入，系统已阻止该会话进入游戏区。"))
        }
    }
}

