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

package icu.h2l.api.vServer

import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.proxy.Player
import icu.h2l.api.command.HyperChatCommandRegistration

/**
 * 登录等待区 / 认证虚拟服 的统一抽象。
 *
 * 这里不再把上层逻辑绑定到具体的 Limbo 或后端等待服实现；
 * 只暴露“进入等待区、注册等待区命令、判断代理兜底命令是否可用、
 * 完成认证后的收尾、主动退出等待区”等公共能力。
 *
 * 注意：实现特有的运行态必须由各实现自行私有维护，
 * 不应再向上抽象成混合式共享状态仓。
 */
interface HyperZoneVServerAdapter {
    fun isEnabled(): Boolean = true

    /**
     * 让一个已经在线的玩家重新进入等待区流程。
     *
     * 该入口主要用于：
     * - `/hzl re` 之类的主动重新认证；
     * - 已在代理内的玩家重新回到等待区。
     *
     * 初次进入代理时的等待区接入，应由具体实现监听原生登录/选服事件自行处理。
     */
    fun reJoin(player: Player)

    /**
     * 兼容旧接口名。
     */
    @Deprecated(
        message = "Use reJoin(player) instead",
        replaceWith = ReplaceWith("reJoin(player)")
    )
    fun authPlayer(player: Player) {
        reJoin(player)
    }

    /**
     * 判断玩家当前是否仍物理处于等待区实现内部。
     *
     * 这里和 [icu.h2l.api.player.HyperZonePlayer.isInWaitingArea] 不同：
     * 后者描述的是“认证/Profile 链路是否仍未完成”，
     * 而这里要求由具体实现返回“玩家当前是否还在 Limbo / 后端认证等待服”这一运行态。
     */
    fun isPlayerInWaitingArea(player: Player): Boolean = false

    fun registerCommand(meta: CommandMeta, registration: HyperChatCommandRegistration) {
    }

    fun supportsProxyFallbackCommands(): Boolean = false

    fun canUseProxyFallbackCommand(player: Player): Boolean = false

    /**
     * 退出当前等待区。
     *
     * 注意：这里的“退出”语义由具体实现决定，但必须符合该实现的原生行为。
     * - 对真实后端等待服实现，应尽量把玩家送回进入等待区前的目标服务器；
     * - 对 Limbo 实现，断开 Limbo 会话本身就是退出等待区。
     *
     * @return 是否已接受本次退出请求
     */
    fun exitWaitingArea(player: Player): Boolean = false

    fun onVerified(player: Player) {
    }
}

interface HyperZoneVServerProvider {
    /**
     * Returns the active waiting-area adapter if available.
     */
    val serverAdapter: HyperZoneVServerAdapter?
}
