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
 * 这里不再把上层逻辑绑定到具体的等待区实现；
 * 只暴露“进入等待区、注册等待区命令、判断代理兜底命令是否可用、
 * 完成认证后的收尾、主动退出等待区”等公共能力。
 *
 * 注意：实现特有的运行态必须由各实现自行私有维护，
 * 不应再向上抽象成混合式共享状态仓。
 */
interface HyperZoneVServerAdapter {
    /**
     * 当前等待区适配器是否已启用。
     */
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
     * 这里和 [icu.h2l.api.player.HyperZonePlayer.hasAttachedUser] 不同：
     * 后者描述的是“玩家是否已 attach 正式游戏档案”，
     * 而这里要求由具体实现返回“玩家当前是否仍在该等待区实现内部”这一运行态。
     */
    fun isPlayerInWaitingArea(player: Player): Boolean = false

    /**
     * 向等待区实现注册一条聊天命令。
     *
     * 当等待区实现本身接管命令输入时，可通过该入口注册只在等待区可用的命令。
     */
    fun registerCommand(meta: CommandMeta, registration: HyperChatCommandRegistration) {
    }

    /**
     * 当前等待区实现是否支持由代理层兜底执行命令。
     */
    fun supportsProxyFallbackCommands(): Boolean = false

    /**
     * 判断指定玩家当前是否允许使用代理层兜底命令。
     */
    fun allowsProxyFallbackCommand(player: Player): Boolean = false

    /**
     * 当前等待区实现是否需要后端 PlayerInfo/TabList 兼容补偿。
     *
     * 该能力主要服务于 backend 模式下的真实等待服；
     * outpre 应优先在自己的桥接链路中直接处理，而不是依赖额外 Netty 补丁擦屁股。
     */
    fun needsBackendPlayerInfoCompat(): Boolean = false


    /**
     * 当前等待区实现是否需要 attach 后的运行时 GameProfile 补偿同步。
     *
     * backend 模式在玩家已经注册进 Velocity 后，仍可能需要对在线索引做补偿；
     * outpre 应在最终交付给 Velocity 之前自行完成最终 User 挂载。
     */
    fun needsBackendRuntimeProfileSync(): Boolean = false

    /**
     * 当前等待区实现是否需要在代理初始登录阶段做 backend 专用的临时 User 替换/冲突校验。
     *
     * backend 模式依赖该流程保证进入 Velocity 后到离开等待服前一直使用可信临时身份；
     * outpre 全链路自行掌控，不应再复用这段兼容逻辑。
     */
    fun needsBackendInitialProfileCompat(): Boolean = false


    /**
     * 退出当前等待区。
     *
     * 注意：这里的“退出”语义由具体实现决定，但必须符合该实现的原生行为。
     * - 对真实后端等待服实现，应尽量把玩家送回进入等待区前的目标服务器；
     * - 对自维护连接的实现，退出语义由实现自行定义。
     *
     * @return 是否已接受本次退出请求
     */
    fun exitWaitingArea(player: Player): Boolean = false

    /**
     * 在玩家完成验证后通知等待区实现做收尾处理。
     */
    fun onVerified(player: Player) {
    }
}

interface HyperZoneVServerProvider {
    /**
     * 当前活动中的等待区适配器；若核心未启用等待区实现则返回 `null`。
     */
    val serverAdapter: HyperZoneVServerAdapter?
}
