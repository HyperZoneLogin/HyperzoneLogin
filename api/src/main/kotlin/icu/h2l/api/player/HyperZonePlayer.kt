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
import icu.h2l.api.db.InGameProfile
import icu.h2l.api.db.User
import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.api.profile.HyperZoneCredentialFlowProvider
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
     * 当前玩家已绑定的 HZL User UUID；未绑定时为 null。
     *
     * 该值由核心 User 服务在 attach 成功后写入，模块不得直接修改。
     * 一个玩家在当前登录会话中最多对应一个 HZL User。
     */
    val userUUID: UUID?

    /**
     * 客户端本次连接开始时实际使用的登录身份快照。
     *
     * 该值只表示“客户端最初连进来时用的是谁”，在连接开始时捕获；
     * 渠道后续返回的认证名称/UUID 不得覆盖它。
     *
     * 它不代表 HZL User，也不是活动 InGameProfile。
     * 只有登录成功包在 attach 之前发送时（等待区桥接流程）才会直接使用它；
     * 一旦登录成功包已经写出，后续皮肤 tab 回放应使用 [loginSuccessIdentity]，
     * 不能再从这里或活动 InGameProfile 重新推导。
     */
    val loginIdentity: ClientLoginIdentity

    /**
     * 本次连接实际写入 `ServerLoginSuccessPacket` 的身份快照。
     *
     * 这是一个“已经发给客户端的协议事实”，不是新的身份类型：
     * - 等待区桥接流程在 attach 之前发送 success，此时没有 IGP，身份为 [loginIdentity]；
     * - 阻塞认证流程在 attach 之后发送 success，身份为活动 InGameProfile 的 name/uuid。
     *
     * 不能在皮肤 tab / self skin replay 里直接读活动 InGameProfile，原因：
     * 1. 桥接流程发 success 时还没有 IGP，客户端认领的是 [loginIdentity]；
     *    等 attach 后再用 IGP 回放，uuid/name 会和客户端已收到的 success 不一致。
     * 2. 即使当前有 IGP，活动身份之后也可能切换；而客户端自己的 uuid/name
     *    在本次连接里已经被第一次 success 固定，不能事后更换。
     * 3. `ServerLoginSuccessEvent` 允许监听器改写最终 packet，实际写出的身份
     *    不一定是调用点最初传入的那个。
     *
     * 因此核心在真正写出 success 时记录最终身份；self skin replay / tab
     * `ADD_PLAYER` 必须复用该值，保证与客户端看到的 uuid/name 严格一致。
     * 未显式设置时回退到 [loginIdentity]。
     */
    val loginSuccessIdentity: ClientLoginIdentity
        get() = loginIdentity

    /**
     * 记录本次登录成功包实际使用的身份。
     *
     * 由核心在写出 `ServerLoginSuccessPacket` 时调用（包含事件监听器改写后的最终字段）；
     * 模块不得自行改写。该值用于后续皮肤 tab / self skin replay 与登录成功包对齐。
     */
    fun markLoginSuccessIdentity(identity: ClientLoginIdentity) {
        // 默认无操作；真实运行时（Velocity）会缓存该身份。
    }

    /**
     * 当前会话最近提交的认证凭证快照。
     *
     * 由核心在提交/替换凭证时写入；用于读取渠道登录信息。
     */
    val submittedCredential: HyperZoneCredential?
        get() = null

    /**
     * 当前登录会话已 attach 的 HZL User 对象。
     *
     * 由核心在 attach 时写入；模块不得直接修改。
     */
    val attachedUser: User?
        get() = null

    /**
     * 当前登录会话使用的活动 InGameProfile 快照。
     *
     * 由核心在 attach 或切换活动身份时写入；游戏内名称/UUID 必须从这里读取。
     */
    val activeInGameProfile: InGameProfile?
        get() = null

    /**
     * 仅用于日志/展示的标签，不是可信身份。
     *
     * 优先使用已绑定的代理玩家名，其次 HZL userUUID。
     */
    val logLabel: String
        get() = getProxyPlayerOrNull()?.username ?: userUUID?.toString() ?: "<unknown>"

    /**
     * 当前会话完成认证的渠道 ID。
     *
     * 该值在登录管理器提交凭证时由核心层自动记录；
     * 在凭证提交前（或 [resetVerify] 后）返回 null。
     *
     * 示例值：`"floodgate"`、`"offline"`、`"yggdrasil:mojang"`。
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
     * 标记客户端已收到 login success（进入 PLAY 阶段）。
     *
     * 由核心在观察到 `ServerLoginSuccessPacket` 时调用；供需要感知「客户端是否已离开登录阶段」
     * 的子模块读取（例如 chat session 拦截）。默认无操作，由运行时实现覆写。
     */
    fun markLoginSuccessSent() {
        // 默认无操作；真实运行时（Velocity）会记录该标志。
    }

    /**
     * 客户端是否已收到 login success（进入 PLAY 阶段）。
     */
    fun hasLoginSuccessBeenSent(): Boolean = false

    /**
     * 当前玩家是否已经 attach 到一个 HZL User。
     *
     * 该状态由核心层 User 服务统一维护；认证子模块不应直接改写。
     */
    fun hasAttachedUser(): Boolean

    /**
     * 获取当前会话已提交的全部凭证快照。
     */
    fun getSubmittedCredentials(): List<HyperZoneCredential> =
        HyperZoneCredentialFlowProvider.getOrNull()?.getSubmittedCredentials(this).orEmpty()

    /**
     * 判断是否允许进行绑定流程。
     *
     * 该判断通常用于“当前会话已有可信凭证，但尚未 attach 正式档案”的场景。
     */
    fun canBind(): Boolean = getSubmittedCredentials().isNotEmpty()


    /**
     * 将玩家重新置为未验证状态。
     *
     * 主要用于主动登出、敏感操作后重新鉴权等场景。
     */
    fun resetVerify() {
        HyperZoneCredentialFlowProvider.getOrNull()?.resetVerify(this)
    }

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
     * 获取玩家的正式身份档案（仅含用户名与 UUID，**不含 properties**）。
     *
     * 该方法只用于需要"干净"身份信息（名字 + UUID）的内部场景，
     * 例如：凭证绑定校验、日志记录、User 服务写库等。
     *
     * **不应在正常游戏流程中直接使用此方法向后端转发档案**，
     * 因为返回值不包含游戏属性（如皮肤）；游戏流程请使用 [getApplyGameProfile]。
     *
     * 调用方应确保当前玩家已 attach User；否则实现可以直接抛错，
     * 以暴露"未完成 profile 链路却尝试进入游戏区"的逻辑问题。
     */
    fun getAttachedGameProfile(): GameProfile

    /**
     * 获取用于向后端转发、完整携带所有游戏属性的正式 GameProfile。
     *
     * 这是**正常游戏流程中应优先使用的档案获取入口**。
     * 实现会在 [getAttachedGameProfile] 返回的身份基础上，
     * 触发属性应用事件（`ProfilePropertiesApplyEvent`）并由各功能模块
     * （如皮肤模块）填充具体的 properties。
     *
     * 返回 null 表示当前玩家尚未 attach User 或属性解析失败；
     * 调用方应确保在玩家已完成 User 链路后再调用此方法。
     *
     * 默认实现直接返回 [getAttachedGameProfile]（无额外属性），
     * 各运行时层（如 Velocity）应覆写以触发属性填充流程。
     */
    fun getApplyGameProfile(): GameProfile? {
        return runCatching { getAttachedGameProfile() }.getOrNull()
    }
}