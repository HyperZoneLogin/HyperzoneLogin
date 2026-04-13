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

package icu.h2l.api.event.area

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.player.HyperZonePlayer

/**
 * 玩家被观测为“进入游戏区”时触发的提示性事件。
 *
 * 注意：该事件当前仅用于向玩家发送提示消息、展示 UI 等弱依赖场景。
 * 由于验证完成、离开等待区、目标服连接完成三者之间的时序目前仍不完全稳定，
 * 该事件暂时不保证稳定、完整、唯一触发。
 *
 * 因此请不要使用该事件驱动：
 * - 权限控制
 * - 状态机切换
 * - 传送/踢出/放行逻辑
 * - 任何要求强一致性的业务流程
 */
class GameAreaEnterEvent(
    val proxyPlayer: Player,
    val hyperZonePlayer: HyperZonePlayer,
    val reason: PlayerAreaTransitionReason
)

