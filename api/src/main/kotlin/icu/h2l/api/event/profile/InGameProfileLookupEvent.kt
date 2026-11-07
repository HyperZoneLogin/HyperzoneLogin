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

package icu.h2l.api.event.profile

import com.velocitypowered.api.event.annotation.AwaitingEvent
import icu.h2l.api.db.InGameProfileCandidate
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 反向查询事件：按 HZL 账号 ID 收集各渠道已知的游戏内身份。
 *
 * 认证子模块必须监听该事件，并在自身数据表中按 `profile_id` 反查所有记录，
 * 通过 [contribute] 提交候选。核心层会统一执行冲突检查后写入 InGameProfile。
 *
 * 该事件为同步等待事件，核心会在所有监听器完成后读取 [candidates]。
 *
 * @property userUUID 要反向查询的 HZL 账号 ID
 */
@AwaitingEvent
class InGameProfileLookupEvent(
    val userUUID: UUID,
) {
    private val collected = CopyOnWriteArrayList<InGameProfileCandidate>()

    /**
     * 提交一条渠道记录。重复提交由核心层按渠道来源去重。
     */
    fun contribute(candidate: InGameProfileCandidate) {
        collected += candidate
    }

    /**
     * 读取当前已收集到的全部候选。
     */
    fun candidates(): List<InGameProfileCandidate> = collected.toList()
}
