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

import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.HyperZoneLogApi
import icu.h2l.api.log.HyperZoneLogger
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.api.log.debug as apiDebug
import icu.h2l.api.log.info as apiInfo

private object VelocityLoggerBridge : HyperZoneLogger {
    override fun info(message: String) {
        val logger = HyperZoneLoginMain.getInstance().logger
        if (logger.isInfoEnabled) {
            logger.info(message)
        }
    }

    override fun debug(type: HyperZoneDebugType, message: String) {
        val logger = HyperZoneLoginMain.getInstance().logger
        if (isDebugEnabled(type)) {
//            不要改成debug，不需要logger开启debug
            logger.info("[DEBUG][{}] {}", type.name, message)
        }
    }

    override fun isDebugEnabled(type: HyperZoneDebugType): Boolean {
        return HyperZoneLoginMain.getDebugConfig().isEnabled(type)
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

internal inline fun debug(type: HyperZoneDebugType, block: () -> String) {
    apiDebug(type, block)
}
