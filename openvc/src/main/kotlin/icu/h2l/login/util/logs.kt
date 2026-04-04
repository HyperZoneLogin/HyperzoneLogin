package icu.h2l.login.util

import icu.h2l.api.log.HyperZoneLogApi
import icu.h2l.api.log.HyperZoneLogger
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.api.log.debug as apiDebug
import icu.h2l.api.log.info as apiInfo

private const val DEBUG_MESSAGE_PREFIX = "[DEBUG] "

private object OpenVcLoggerBridge : HyperZoneLogger {
    override fun info(message: String) {
        val logger = HyperZoneLoginMain.getInstance().logger
        if (logger.isInfoEnabled) {
            logger.info(message)
        }
    }

    override fun debug(message: String) {
        val debugEnabled = runCatching { HyperZoneLoginMain.getOfflineMatchConfig().advanced.debug }.getOrDefault(false)
        if (debugEnabled) {
            info("$DEBUG_MESSAGE_PREFIX$message")
        }
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
    HyperZoneLogApi.registerLogger(OpenVcLoggerBridge)
}

internal inline fun info(block: () -> String) {
    apiInfo(block)
}

internal inline fun debug(block: () -> String) {
    apiDebug(block)
}