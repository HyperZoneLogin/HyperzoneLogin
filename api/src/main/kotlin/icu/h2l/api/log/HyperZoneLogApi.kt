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

package icu.h2l.api.log

/**
 * HyperZoneLogin 内部使用的 debug 日志类型。
 */
enum class HyperZoneDebugType {
    /**
     * 未细分的通用调试日志；旧版 `debug {}` 默认归类到这里。
     */
    GENERAL,

    /**
     * Floodgate / OutPre 预登录链路追踪日志。
     */
    OUTPRE_TRACE,

    /**
     * 皮肤预处理、缓存、回放等日志。
     */
    PROFILE_SKIN,

    /**
     * 后端等待区兼容链路日志。
     */
    BACKEND_COMPAT,

    /**
     * Netty / GameProfile 重写链路日志。
     */
    NETWORK_REWRITE,

    /**
     * Yggdrasil 认证流程与配置加载日志。
     */
    YGGDRASIL_AUTH,
}

/**
 * HyperZoneLogin 对外暴露的最小日志接口。
 */
interface HyperZoneLogger {
    /**
     * 记录一条信息级日志。
     */
    fun info(message: String)

    /**
     * 记录一条调试级日志。
     */
    fun debug(type: HyperZoneDebugType, message: String)

    /**
     * 当前是否启用了指定类型的调试日志。
     */
    fun isDebugEnabled(type: HyperZoneDebugType): Boolean = false

    /**
     * 兼容旧调用的通用调试日志。
     */
    fun debug(message: String) {
        debug(HyperZoneDebugType.GENERAL, message)
    }

    /**
     * 记录一条警告级日志。
     */
    fun warn(message: String)

    /**
     * 记录一条错误级日志。
     */
    fun error(message: String, throwable: Throwable? = null)
}

/**
 * 全局日志 API 门面。
 */
object HyperZoneLogApi {
    @Volatile
    private var logger: HyperZoneLogger = NoopHyperZoneLogger

    @JvmStatic
    /**
     * 注册当前运行时要使用的日志实现。
     */
    fun registerLogger(newLogger: HyperZoneLogger) {
        logger = newLogger
    }

    @JvmStatic
    /**
     * 获取当前活动的日志实现。
     */
    fun getLogger(): HyperZoneLogger = logger
}

private object NoopHyperZoneLogger : HyperZoneLogger {
    override fun info(message: String) = Unit
    override fun debug(type: HyperZoneDebugType, message: String) = Unit
    override fun isDebugEnabled(type: HyperZoneDebugType): Boolean = false
    override fun warn(message: String) = Unit
    override fun error(message: String, throwable: Throwable?) = Unit
}

/**
 * 惰性输出一条信息级日志。
 */
inline fun info(block: () -> String) {
    HyperZoneLogApi.getLogger().info(block())
}

/**
 * 惰性输出一条调试级日志。
 */
inline fun debug(block: () -> String) {
    debug(HyperZoneDebugType.GENERAL, block)
}

/**
 * 惰性输出一条指定类型的调试级日志。
 */
inline fun debug(type: HyperZoneDebugType, block: () -> String) {
    val logger = HyperZoneLogApi.getLogger()
    if (logger.isDebugEnabled(type)) {
        logger.debug(type, block())
    }
}

/**
 * 直接输出一条指定类型的调试级日志。
 */
fun debug(type: HyperZoneDebugType, message: String) {
    val logger = HyperZoneLogApi.getLogger()
    if (logger.isDebugEnabled(type)) {
        logger.debug(type, message)
    }
}

/**
 * 惰性输出一条警告级日志。
 */
inline fun warn(block: () -> String) {
    HyperZoneLogApi.getLogger().warn(block())
}

/**
 * 惰性输出一条错误级日志。
 */
inline fun error(throwable: Throwable? = null, block: () -> String) {
    HyperZoneLogApi.getLogger().error(block(), throwable)
}