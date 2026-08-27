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
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import net.kyori.adventure.text.Component
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `HyperZonePlayer` 的 Velocity 实现。
 *
 * 这里维护连接/等待区相关的状态，以及少量登录会话辅助状态。
 *
 * 核心会在 `LoginManager` 统一根据凭证 attach 正式 Profile，并负责凭证流编排。
 * 只有"已 attach Profile"条件满足时，
 * 玩家才允许离开等待区并使用正式游戏身份进入游戏区。
 */
class VelocityHyperZonePlayer(
//    最开始客户端传入的，不可信；仅用于调试、客户端回放与第一次拟定默认生成名
    override val clientOriginalName: String,
    override val clientOriginalUUID: UUID?,
    override val isOnlinePlayer: Boolean,
) : HyperZonePlayer {
    private var proxyPlayer: Player? = null
    private val hasBoundProxyPlayer = AtomicBoolean(false)

    /**
     * 玩家是否已经生成过可直接发送消息的实体。
     */
    private val hasSpawned = AtomicBoolean(false)

    /**
     * 玩家进入可收消息阶段前缓存的提示消息。
     */
    private val messageQueue = ConcurrentLinkedQueue<Component>()

    /**
     * 等待区转发用的临时档案。
     *
     * 该档案在当前登录会话创建时即生成完成；
     * 当玩家仍在等待区时，应优先使用该档案而不是正式游戏档案。
     */
    private val temporaryGameProfile: GameProfile = RemapUtils.randomProfile()

    override val authChannelId: String?
        get() = getSubmittedCredentials().firstOrNull()?.channelId

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
}

