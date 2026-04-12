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
import icu.h2l.api.db.Profile
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
     * 当前玩家的用户名（登录流程内的统一名称）。
     */
    val userName: String

    /**
     * 当前玩家的UUID（登录流程内的统一名称）。
     */
    val uuid: UUID

    /**
     * 当前玩家是否已经 attach 到一个已存在的游戏档案。
     *
     * 认证通过并不代表一定已有 Profile；只有 attach 完成后，
     * 才允许离开等待区并使用正式游戏身份进入游戏区。
     */
    fun hasAttachedProfile(): Boolean

    /**
     * 判断当前玩家是否允许请求核心层解析或创建 Profile。
     *
     * 主要依据：数据库中是否已存在该玩家 Profile。
     */
    fun canResolveOrCreateProfile(): Boolean

    /**
     * 使用可信资料请求核心层解析或创建 Profile。
     *
     * 该流程会通过核心统一判断名称/UUID 冲突，并在必要时创建新档案。
     *
     * @return 核心层解析后的 Profile 对象
     */
    fun resolveOrCreateProfile(userName: String? = null, uuid: UUID? = null): Profile

    /**
     * 兼容旧接口名。
     */
    @Deprecated(
        message = "Use canResolveOrCreateProfile() instead",
        replaceWith = ReplaceWith("canResolveOrCreateProfile()")
    )
    fun canRegister(): Boolean {
        return canResolveOrCreateProfile()
    }

    /**
     * 兼容旧接口名。
     */
    @Deprecated(
        message = "Use resolveOrCreateProfile(...) instead",
        replaceWith = ReplaceWith("resolveOrCreateProfile(userName, uuid)")
    )
    fun register(userName: String? = null, uuid: UUID? = null): Profile {
        return resolveOrCreateProfile(userName, uuid)
    }

    /**
     * 将当前玩家绑定到一个已知的 Profile。
     *
     * 适用于认证子模块已经通过可信映射拿到 profileId 的场景。
     *
     * @param profileId 已知的档案ID
     * @return 绑定成功后的 Profile，不存在时返回 null
     */
    fun attachProfile(profileId: UUID): Profile?

    /**
     * 获取当前玩家数据库中对应的 Profile。如果是第一次加入游戏，是获取不到的。只有已注册用户才有。
     *
     * @return Profile，不存在时返回 null
     */
    fun getDBProfile(): Profile?

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
     */
    fun canBind(): Boolean

    /**
     * 结束玩家验证流程。
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
     */
    fun sendMessage(message: Component)

    /**
     * 获取当前连接关联的代理层玩家对象。
     *
     * 仅供需要直接向客户端连接补发协议包的场景使用；
     * 在玩家尚未进入可写阶段时，允许返回 null。
     */
    fun getProxyPlayerOrNull(): Player? {
        return null
    }

    /**
     * 获取客户端最初进入登录链路时携带的原始用户名。
     *
     * 该值仅用于需要“回放给客户端自己”的资料修复链路，
     * 不能替代统一登录身份字段 `userName`。
     */
    fun getClientOriginalName(): String {
        return userName
    }

    /**
     * 获取客户端最初进入登录链路时携带的原始 UUID。
     *
     * 该值仅用于需要“回放给客户端自己”的资料修复链路，
     * 不能替代统一登录身份字段 `uuid`。
     */
    fun getClientOriginalUUID(): UUID {
        return uuid
    }


    /**
     * 获取玩家在等待区阶段应使用的临时 GameProfile。
     *
     * 该档案必须是系统主动生成并受控的临时档案；
     * 认证阶段拿到的初始档案、客户端上报档案等都不应作为等待区身份直接对外使用。
     */
    fun getTemporaryGameProfile(): GameProfile

    /**
     * 获取玩家进入游戏区后应使用的正式 GameProfile。
     *
     * 调用方应确保当前玩家已 attach Profile；否则实现可以直接抛错，
     * 以暴露“未完成 profile 链路却尝试进入游戏区”的逻辑问题。
     */
    fun getAttachedGameProfile(): GameProfile


    /**
     * 设置登录阶段生成的临时 GameProfile。
     *
     * 该档案用于等待区阶段的受控临时身份。
     */
    fun setTemporaryGameProfile(profile: GameProfile?)

    /**
     * 兼容旧接口名。
     */
    @Deprecated(
        message = "Use setTemporaryGameProfile(...) instead",
        replaceWith = ReplaceWith("setTemporaryGameProfile(profile)")
    )
    fun setTemporaryForwardingProfile(profile: GameProfile?) {
        setTemporaryGameProfile(profile)
    }
}