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

package icu.h2l.login.util

import icu.h2l.api.log.HyperZoneLogApi
import icu.h2l.api.log.HyperZoneLogger
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.chatsession.idtable.ChatSessionPacketIdTable
import icu.h2l.api.log.debug as apiDebug
import icu.h2l.api.log.info as apiInfo

private object VelocityLoggerBridge : HyperZoneLogger {
    override fun info(message: String) {
        val logger = HyperZoneLoginMain.getInstance().logger
        if (logger.isInfoEnabled) {
            logger.info(message)
        }
    }

    override fun debug(message: String) {
        // 不要改成 debug，不需要 logger 开启 debug
        HyperZoneLoginMain.getInstance().logger.info("[DEBUG] {}", message)
    }

    override fun warn(message: String) {
        HyperZoneLoginMain.getInstance().logger.warn(message)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            HyperZoneLoginMain.getInstance().logger.error(message, throwable)
        } else {
            HyperZoneLoginMain.getInstance().logger.error(message)
        }
    }
}

fun registerApiLogger() {
    HyperZoneLogApi.registerLogger(VelocityLoggerBridge)
}

internal inline fun info(block: () -> String) {
    apiInfo(block)
}

internal inline fun debug(block: () -> String) {
    apiDebug(block)
}

// ── 核心自身的调试开关 ────────────────────────────────────────────────────────
// 核心只负责按自己的类别判断是否输出；子模块的开关各自维护，不再由核心枚举承载。

internal inline fun debugGeneral(block: () -> String) {
    if (HyperZoneLoginMain.getCoreConfig().debug.log.general) {
        apiDebug(block)
    }
}

internal inline fun debugOutPreTrace(block: () -> String) {
    if (HyperZoneLoginMain.getCoreConfig().debug.log.outPreTrace) {
        apiDebug(block)
    }
}

internal fun debugOutPreTrace(message: String) {
    if (HyperZoneLoginMain.getCoreConfig().debug.log.outPreTrace) {
        apiDebug(message)
    }
}

internal inline fun debugNetworkRewrite(block: () -> String) {
    if (HyperZoneLoginMain.getCoreConfig().debug.log.networkRewrite) {
        apiDebug(block)
    }
}

internal inline fun debugBackendCompat(block: () -> String) {
    if (HyperZoneLoginMain.getCoreConfig().debug.log.backendCompat) {
        apiDebug(block)
    }
}

internal inline fun debugChatSession(block: () -> String) {
    if (ChatSessionPacketIdTable.isDebugEnabled()) {
        apiDebug(block)
    }
}
