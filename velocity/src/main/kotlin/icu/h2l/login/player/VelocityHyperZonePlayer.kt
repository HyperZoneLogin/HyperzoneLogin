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
import icu.h2l.api.event.area.PlayerAreaTransitionReason
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.listener.PlayerAreaLifecycleListener
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.message.MessageKeys
import net.kyori.adventure.text.Component
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `HyperZonePlayer` 的 Velocity 实现。
 *
 * 这里维护三组彼此独立但互相关联的状态：
 * 1. 连接/等待区状态：玩家是否仍在认证等待区实现内部；
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
    private val hasBoundProxyPlayer = AtomicBoolean(false)

    /**
     * 认证链路状态，仅表示子模块是否认可本次登录。
     *
     * 该状态不代表一定能进入游戏区；还需要核心层已根据凭证 attach Profile。
     */
    private val isVerifiedState = AtomicBoolean(false)
    private val hasNotifiedReadyState = AtomicBoolean(false)
    private val submittedCredentials = CopyOnWriteArrayList<HyperZoneCredential>()
    private val coreAuthorizedOverVerify = AtomicBoolean(false)

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
     * 该档案在当前登录会话创建时即生成完成；
     * 当玩家仍在等待区时，应优先使用该档案而不是正式游戏档案。
     */
    private val temporaryGameProfile: GameProfile = RemapUtils.randomProfile()

    /**
     * 当前会话通过认证的凭证渠道 ID；submitCredential 时自动记录，resetVerify 时清空。
     */
    private val authChannelIdRef = AtomicReference<String?>(null)

    override val authChannelId: String?
        get() = authChannelIdRef.get()

    /**
     * 绑定当前登录会话对应的代理层 Player。
     *
     * 注意：每个 `VelocityHyperZonePlayer` 只允许绑定一次。
     * 当前等待区实现会在各自合法的接入阶段完成绑定，不能重复绑定。
     */
    fun injectProxyPlayer(player: Player) {
        if (!hasBoundProxyPlayer.compareAndSet(false, true)) {
            throw IllegalStateException("玩家 $clientOriginalName 的代理玩家对象只能绑定一次")
        }
        proxyPlayer = player
    }

    internal fun suspendMessageDelivery() {
        hasSpawned.set(false)
    }

    internal fun resumeMessageDelivery() {
        if (!hasSpawned.compareAndSet(false, true)) {
            return
        }

        while (messageQueue.isNotEmpty()) {
            val message = messageQueue.poll() ?: continue
            proxyPlayer?.sendMessage(message)
        }
    }

    override fun hasAttachedProfile(): Boolean {
        return HyperZoneLoginMain.getInstance().profileService.hasAttachedProfile(this)
    }


    override fun submitCredential(credential: HyperZoneCredential) {
        if (submittedCredentials.isNotEmpty()) {
            throw IllegalStateException(
                "玩家 $clientOriginalName 已存在凭证 ${submittedCredentials.first().channelId}，" +
                    "必须先调用 destroyCredential() 移除旧凭证后再提交新凭证"
            )
        }
        authChannelIdRef.set(credential.channelId)
        submittedCredentials += credential
    }

    override fun destroyCredential(channelId: String) {
        submittedCredentials.removeIf { it.channelId == channelId }
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
        if (HyperZoneLoginMain.getCoreConfig().debug.slowTest.enabled && !coreAuthorizedOverVerify.get()) {
            sendMessage(HyperZoneLoginMain.getInstance().messageService.render(this, MessageKeys.Over.BLOCKED_BY_SLOW_TEST))
            return
        }

        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "hyperPlayer.overVerify before player=$clientOriginalName verified=${isVerifiedState.get()} attachedProfile=${hasAttachedProfile()} credentials=${submittedCredentials.map { it.javaClass.simpleName }} proxyBound=${proxyPlayer != null}"
        }

        HyperZoneLoginMain.getInstance().profileService.attachVerifiedCredentialProfile(this)
        isVerifiedState.set(true)
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "hyperPlayer.overVerify after-attach player=$clientOriginalName verified=${isVerifiedState.get()} attachedProfile=${hasAttachedProfile()}"
        }
        if (!hasAttachedProfile()) {
            sendMessage(HyperZoneLoginMain.getInstance().messageService.render(this, MessageKeys.Player.VERIFIED_UNBOUND))
        }
        tryLeaveWaiting()
    }

    internal fun runCoreAuthorizedOverVerify() {
        coreAuthorizedOverVerify.set(true)
        try {
            overVerify()
        } finally {
            coreAuthorizedOverVerify.set(false)
        }
    }

    override fun resetVerify() {
        isVerifiedState.set(false)
        hasNotifiedReadyState.set(false)
        submittedCredentials.clear()
        authChannelIdRef.set(null)
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
    }

    override fun getAttachedGameProfile(): GameProfile {
        val resolvedProfile = HyperZoneLoginMain.getInstance().profileService.getAttachedProfile(this)
            ?: throw IllegalStateException("玩家 $clientOriginalName 尚未 attach Profile，无法获取正式游戏档案")
        return GameProfile(
            resolvedProfile.uuid,
            resolvedProfile.name,
            emptyList()
        )
    }

    override fun getApplyGameProfile(): GameProfile? {
        return ProfileSkinApplySupport.apply(this)
    }

    internal fun onAttachedProfileAvailable() {
        tryLeaveWaiting()
    }

    private fun tryLeaveWaiting() {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "hyperPlayer.tryLeaveWaiting player=$clientOriginalName verified=${isVerifiedState.get()} attachedProfile=${hasAttachedProfile()} proxyBound=${proxyPlayer != null}"
        }

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

            PlayerAreaLifecycleListener.markWaitingAreaLeavePending(player, PlayerAreaTransitionReason.VERIFIED)
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

        sendMessage(HyperZoneLoginMain.getInstance().messageService.render(this, MessageKeys.Player.PROFILE_CONFLICT_SELF))
        conflictingPlayers.forEach { conflictingPlayer ->
            if (conflictingPlayer === this) {
                return@forEach
            }
            conflictingPlayer.sendMessage(
                HyperZoneLoginMain.getInstance().messageService.render(
                    conflictingPlayer,
                    MessageKeys.Player.PROFILE_CONFLICT_OTHER
                )
            )
        }
    }
}

