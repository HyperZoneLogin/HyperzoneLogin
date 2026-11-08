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

import java.util.UUID

/**
 * 客户端本次连接开始时实际使用的登录身份快照。
 *
 * 该记录由核心在预登录阶段捕获。
 * 当登录成功包在 attach 之前发送（等待区桥接流程）时，会使用该身份；
 * attach 后发送（阻塞认证流程）时则以活动 InGameProfile 为准。
 *
 * 它不是 HZL User，也不是 InGameProfile；渠道后续返回的认证名称/UUID
 * 不得覆盖本记录。
 *
 * @property name 客户端登录名称
 * @property uuid 客户端登录 UUID
 */
data class ClientLoginIdentity(
    val name: String,
    val uuid: UUID,
)
