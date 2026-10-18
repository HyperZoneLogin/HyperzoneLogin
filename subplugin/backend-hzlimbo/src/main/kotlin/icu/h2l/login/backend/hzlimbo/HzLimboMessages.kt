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

package icu.h2l.login.backend.hzlimbo

import com.velocitypowered.api.command.CommandSource
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import net.kyori.adventure.text.Component

/**
 * backend-hzlimbo 模块的本地化文案入口。
 */
object HzLimboMessages {
    private const val NAMESPACE = "backend-hzlimbo"

    fun usageStatus(source: CommandSource) = render(source, "usage.status", "/hzlimbo status")
    fun usageStart(source: CommandSource) = render(source, "usage.start", "/hzlimbo start")
    fun usageStop(source: CommandSource) = render(source, "usage.stop", "/hzlimbo stop")
    fun usageRestart(source: CommandSource) = render(source, "usage.restart", "/hzlimbo restart")
    fun usageConnections(source: CommandSource) = render(source, "usage.connections", "/hzlimbo connections")

    fun statusRunning(source: CommandSource) = render(source, "status.running", "HzLimbo 后端正在运行")
    fun statusStopped(source: CommandSource) = render(source, "status.stopped", "HzLimbo 后端已停止")

    fun statusAddress(source: CommandSource, address: String) =
        render(source, "status.address", "绑定地址: <address>", HyperZoneMessagePlaceholder.text("address", address))

    fun statusUptime(source: CommandSource, uptime: String) =
        render(source, "status.uptime", "运行时长: <uptime>", HyperZoneMessagePlaceholder.text("uptime", uptime))

    fun statusConnections(source: CommandSource, count: Int) =
        render(source, "status.connections", "当前连接: <count>", HyperZoneMessagePlaceholder.text("count", count))

    fun statusLastFailure(source: CommandSource, reason: String) =
        render(source, "status.last-failure", "上次启动失败: <reason>", HyperZoneMessagePlaceholder.text("reason", reason))

    fun startStarting(source: CommandSource) = render(source, "start.starting", "正在启动 HzLimbo 后端...")
    fun startSuccess(source: CommandSource, address: String) =
        render(source, "start.success", "HzLimbo 后端已启动: <address>", HyperZoneMessagePlaceholder.text("address", address))
    fun startFailed(source: CommandSource, reason: String) =
        render(source, "start.failed", "HzLimbo 后端启动失败: <reason>", HyperZoneMessagePlaceholder.text("reason", reason))
    fun startAlreadyRunning(source: CommandSource) = render(source, "start.already-running", "HzLimbo 后端已在运行中")

    fun stopStopping(source: CommandSource) = render(source, "stop.stopping", "正在停止 HzLimbo 后端...")
    fun stopSuccess(source: CommandSource) = render(source, "stop.success", "HzLimbo 后端已停止")
    fun stopNotRunning(source: CommandSource) = render(source, "stop.not-running", "HzLimbo 后端本就未在运行")

    fun restartRestarting(source: CommandSource) = render(source, "restart.restarting", "正在重启 HzLimbo 后端...")
    fun restartSuccess(source: CommandSource, address: String) =
        render(source, "restart.success", "HzLimbo 后端已重启: <address>", HyperZoneMessagePlaceholder.text("address", address))
    fun restartFailed(source: CommandSource, reason: String) =
        render(source, "restart.failed", "重启 HzLimbo 后端失败: <reason>", HyperZoneMessagePlaceholder.text("reason", reason))

    fun connectionsSummary(source: CommandSource, count: Int) =
        render(source, "connections.summary", "当前连接数: <count>", HyperZoneMessagePlaceholder.text("count", count))
    fun connectionsEmpty(source: CommandSource) = render(source, "connections.empty", "当前没有玩家连接")

    fun busy(source: CommandSource) = render(source, "busy", "另一个后端管理操作正在执行，请稍后再试")

    fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "0s"
        if (millis < 1000L) return "<1s"
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return buildString {
            if (hours > 0L) append("${hours}h ")
            if (hours > 0L || minutes > 0L) append("${minutes}m ")
            append("${seconds}s")
        }
    }

    private fun render(
        source: CommandSource,
        key: String,
        fallback: String,
        vararg placeholders: HyperZoneMessagePlaceholder,
    ): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(source, "$NAMESPACE.$key", *placeholders)
            ?: Component.text(placeholders.fold(fallback) { acc, placeholder ->
                val replacement = when (placeholder) {
                    is HyperZoneMessagePlaceholder.Text -> placeholder.value
                    is HyperZoneMessagePlaceholder.ComponentValue -> placeholder.value.toString()
                }
                acc.replace("<${placeholder.name}>", replacement)
            })
    }
}
