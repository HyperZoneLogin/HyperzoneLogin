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

package icu.h2l.api.player

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.profile.HyperZoneCredential
import net.kyori.adventure.text.Component
import java.util.*

/**
 * HyperZone 登录流程中的统一玩家抽象。
 *
 * 该对象用于封装登入流程中常用的能力，
 * 让各模块不再直接依赖底层 Limbo 会话处理实现。
 */
interface HyperZonePlayer {
    /**
     * 客户端在进入登录链路时上报的原始用户名。
     *
     * 该值明确为“不可信身份”，不会因为后续 attach Profile 而被改写。
     * 它只允许用于：
     * 1. 调试日志；
     * 2. 第一次为玩家拟定默认生成名；
     * 3. 少量弱匹配/客户端回放场景。
     *
     * 严禁把它当成正式游戏身份、可信认证结果或已绑定 Profile 身份使用。
     */
    val clientOriginalName: String

    /**
     * 客户端在进入登录链路时上报的原始 UUID。
     *
     * 该值明确为“不可信身份”，不会因为后续 attach Profile 而被改写。
     * 它只允许用于调试、客户端回放或极弱的候选发现流程；
     * 严禁把它作为可信认证结果或正式游戏 UUID 使用。
     */
    val clientOriginalUUID: UUID

    /**
     * 当前会话完成认证的渠道 ID。
     *
     * 该值在认证模块通过 [submitCredential] 提交凭证时由核心层自动记录；
     * 在凭证提交前（或 [resetVerify] 后）返回 null。
     *
     * 示例值：`"floodgate"`、`"offline"`、`"yggdrasil"`。
     */
    val authChannelId: String?
        get() = null

    /**
     * 当前连接在预登录阶段最终判定出的在线模式。
     *
     * 该值只在第一次创建登录期玩家对象时确定，之后只允许读取，不允许修改。
     */
    val isOnlinePlayer: Boolean

    /**
     * 当前玩家是否已经 attach 到一个正式游戏档案。
     *
     * 该状态由核心层 Profile 服务统一维护；认证子模块不应直接改写。
     */
    fun hasAttachedProfile(): Boolean

    /**
     * 向当前登录会话提交一个已认证凭证。
     *
     * 子模块应在"认证成功后、调用 overVerify() 前"提交凭证，
     * 由核心在完成验证时统一根据凭证 attach 正式 Profile。
     *
     * 每个玩家同一时刻只能持有一个凭证。若当前会话已存在任意凭证，
     * 则必须先调用 [destroyCredential] 移除旧凭证，再提交新凭证；
     * 直接重复提交将抛出 [IllegalStateException]。
     *
     * @param credential 要提交到当前会话的可信凭证
     * @throws IllegalStateException 若当前会话已存在凭证
     */
    fun submitCredential(credential: HyperZoneCredential)

    /**
     * 销毁当前会话中指定渠道的凭证。
     *
     * 子模块在响应 rename / reuuid 事件时，应先销毁旧凭证，
     * 再以新状态重新提交，确保凭证始终保持不可变性。
     *
     * @param channelId 要销毁的凭证所属渠道标识
     */
    fun destroyCredential(channelId: String) {}

    /**
     * 获取当前会话已提交的全部凭证快照。
     */
    fun getSubmittedCredentials(): List<HyperZoneCredential>

    /**
     * 当前玩家是否仍处于等待区。
     *
     * 等待区判定同时取决于两条链路：
     * 1. 认证链路：必须完成验证；
     * 2. Profile 链路：必须已经 attach 游戏档案。
     *
     * 任一条件不满足，都必须继续停留在等待区。
     */
    fun isInWaitingArea(): Boolean {
        return !isVerified() || !hasAttachedProfile()
    }

    /**
     * 当前玩家是否已完成验证。
     */
    fun isVerified(): Boolean

    /**
     * 判断是否允许进行绑定流程。
     *
     * 该判断通常用于“当前会话已有可信凭证，但尚未 attach 正式档案”的场景。
     */
    fun canBind(): Boolean

    /**
     * 结束玩家验证流程。
     *
     * 实现通常应在该时刻推动从等待区进入正式游戏链路。
     */
    fun overVerify()

    /**
     * 将玩家重新置为未验证状态。
     *
     * 主要用于主动登出、敏感操作后重新鉴权等场景。
     */
    fun resetVerify()

    /**
     * 发送消息给玩家。
     *
     * @param message 要发送给玩家的 Adventure 组件消息
     */
    fun sendMessage(message: Component)

    /**
     * 获取当前连接关联的代理层玩家对象。
     *
     * 仅供需要直接向客户端连接补发协议包的场景使用；
     * 在玩家尚未进入可写阶段时，允许返回 null。
     *
     * 注意：该方法不是“根据 Player 反查 HyperZonePlayer”的入口。
     * 在 `DisconnectEvent`、切服事件、状态清理等场景中，
     * 不要通过遍历所有 `HyperZonePlayer` 并比较 `getProxyPlayerOrNull()` 的方式做反向定位；
     * 应优先使用 `HyperZonePlayerAccessor.getByPlayer(...)` 或 `getByChannel(...)` 做正向映射。
     */
    fun getProxyPlayerOrNull(): Player? {
        return null
    }

    /**
     * 获取玩家在等待区阶段应使用的临时 GameProfile。
     *
     * 该档案必须在当前登录会话创建时由系统主动生成并受控持有；
     * 后续流程只能读取，不应在运行中再次改写；
     * 认证阶段拿到的初始档案、客户端上报档案等都不应作为等待区身份直接对外使用。
     */
    fun getTemporaryGameProfile(): GameProfile

    /**
     * 获取玩家的正式身份档案（仅含用户名与 UUID，**不含皮肤纹理**）。
     *
     * 该方法只用于需要"干净"身份信息（名字 + UUID）的内部场景，
     * 例如：凭证绑定校验、日志记录、Profile 服务写库等。
     *
     * **不应在正常游戏流程中直接使用此方法向后端转发档案**，
     * 因为返回值不包含皮肤数据；游戏流程请使用 [getApplyGameProfile]。
     *
     * 调用方应确保当前玩家已 attach Profile；否则实现可以直接抛错，
     * 以暴露"未完成 profile 链路却尝试进入游戏区"的逻辑问题。
     */
    fun getAttachedGameProfile(): GameProfile

    /**
     * 获取用于向后端转发、完整携带皮肤纹理的正式 GameProfile。
     *
     * 这是**正常游戏流程中应优先使用的档案获取入口**。
     * 实现会在 [getAttachedGameProfile] 返回的身份基础上，
     * 触发皮肤应用事件（如 `ProfileSkinApplyEvent`）并合并最终皮肤纹理。
     *
     * 返回 null 表示当前玩家尚未 attach Profile 或皮肤解析失败；
     * 调用方应确保在玩家已完成 Profile 链路后再调用此方法。
     *
     * 默认实现直接返回 [getAttachedGameProfile]（无皮肤），
     * 各运行时层（如 Velocity）应覆写以注入实际皮肤数据。
     */
    fun getApplyGameProfile(): GameProfile? {
        return runCatching { getAttachedGameProfile() }.getOrNull()
    }
}