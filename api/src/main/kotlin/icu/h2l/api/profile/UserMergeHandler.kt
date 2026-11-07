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

package icu.h2l.api.profile

import java.util.*

/**
 * HZL User 聚合的模块扩展点。
 *
 * 核心在合并两个 User 时，会在删除 source 之前同步调用所有已注册处理器，
 * 每个模块必须在这里把自己表里的 `user_uuid` / `pid` 等绑定从 source 迁移到 target。
 *
 * 处理器必须遵守：
 *
 * - 只迁移渠道账号绑定，不得读取或写入 InGameProfile；
 * - 幂等：同一个 `(source, target)` 重复调用不得产生重复记录；
 * - source 与 target 都有同类绑定时，保留 target 的绑定并清理 source 的重复绑定；
 * - 迁移失败必须抛异常，核心会中止合并。
 */
interface UserMergeHandler {
    /**
     * 处理器唯一标识，用于模块重复注册时覆盖，不会重复执行。
     */
    val handlerId: String

    /**
     * 将 source User 的渠道绑定迁移到 target User。
     *
     * 该方法由核心同步调用；返回后核心才会继续迁移 InGameProfile 并删除 source。
     */
    fun migrateUserBindings(sourceUserUUID: UUID, targetUserUUID: UUID)
}

/**
 * [UserMergeHandler] 的全局注册表。
 *
 * 子模块应在 `HyperSubModule.register(...)` 阶段完成注册。
 */
object UserMergeHandlerRegistry {
    private val handlers = LinkedHashMap<String, UserMergeHandler>()

    @Synchronized
    fun register(handler: UserMergeHandler) {
        handlers[handler.handlerId] = handler
    }

    @Synchronized
    fun unregister(handlerId: String) {
        handlers.remove(handlerId)
    }

    @Synchronized
    fun getHandlers(): List<UserMergeHandler> {
        return handlers.values.toList()
    }
}
