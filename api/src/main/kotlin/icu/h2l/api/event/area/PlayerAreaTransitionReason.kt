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

/**
 * 玩家在等待区 / 游戏区之间切换时的原因。
 *
 * 注意：该枚举只用于补充描述区域提示事件的“触发意图”，
 * 不代表底层已经完成稳定、唯一、可依赖的区域状态切换。
 *
 * 在当前阶段，请仅将其用于消息展示、文案分支或弱提示 UI，
 * 不要将其作为权限、传送、放行、状态机等强逻辑的判定依据。
 */
enum class PlayerAreaTransitionReason {
    /**
     * 因为需要开始或重新开始认证流程而进入等待区，或从游戏区返回等待区。
     */
    AUTH_REQUIRED,

    /**
     * 因为验证完成而离开等待区并进入游戏区。
     */
    VERIFIED,

    /**
     * 因为玩家主动执行退出等待区等操作而离开等待区。
     */
    EXIT_REQUEST,

    /**
     * 因为连接断开而离开当前区域。
     */
    DISCONNECT,

    /**
     * 未经过等待区，直接进入游戏区。
     */
    DIRECT_JOIN,
}

