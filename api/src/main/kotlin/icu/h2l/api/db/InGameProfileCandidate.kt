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

package icu.h2l.api.db

import java.util.*

/**
 * 尚未落库的游戏内身份候选。
 *
 * 渠道模块在认证成功或执行反向查询时提交该对象，由核心层统一完成：
 * - UUID / 名称冲突检查；
 * - 与目标 [User] 的关联写入；
 * - 活动游戏身份指针维护。
 *
 * [channel] / [channelEntry] / [channelAccountId] 仅用于渠道侧诊断与反查展示，
 * 不写入 InGameProfile 表。多个渠道只要返回相同 UUID/名称，就应复用同一条身份。
 *
 * 同一 UUID 已存在记录时，核心直接复用该记录，不因本次渠道名称不同而拒绝登录。
 * 禁止用本对象的 name / uuid 覆盖既有 InGameProfile。
 *
 * [uuid] 为 null 表示该渠道没有显式透传 UUID，由核心按注册名生成默认 UUID。
 */
data class InGameProfileCandidate(
    val channel: String,
    val name: String,
    val uuid: UUID? = null,
    val channelEntry: String? = null,
    val channelAccountId: String? = null,
)
