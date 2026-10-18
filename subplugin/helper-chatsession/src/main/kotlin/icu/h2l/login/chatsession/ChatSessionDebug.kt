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

package icu.h2l.login.chatsession

import icu.h2l.api.log.debug as apiDebug
import icu.h2l.login.chatsession.idtable.ChatSessionPacketIdTable

/**
 * helper-chatsession 模块自身的调试日志开关。
 *
 * 开关值来自模块自己的配置（`helper-chatsession.conf` 的 `debug`），
 * 核心层不感知本模块的调试类别。
 */
object ChatSessionDebug {
    inline fun log(block: () -> String) {
        if (ChatSessionPacketIdTable.isDebugEnabled()) {
            apiDebug(block)
        }
    }
}
