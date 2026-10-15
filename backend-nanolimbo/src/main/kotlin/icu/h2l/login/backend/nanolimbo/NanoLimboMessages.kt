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

package icu.h2l.login.backend.nanolimbo

import com.velocitypowered.api.command.CommandSource
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import net.kyori.adventure.text.Component

/**
 * backend-nanolimbo 模块的本地化文案入口。
 *
 * 所有面向管理员的命令输出都必须经过这里，避免在命令实现中散落硬编码字符串。
 */
object NanoLimboMessages {
    private const val NAMESPACE = "backend-nanolimbo"

    fun usageStatus(source: CommandSource): Component = render(source, "usage.status", "/hzl-limbo status")
    fun usageStart(source: CommandSource): Component = render(source, "usage.start", "/hzl-limbo start")
    fun usageStop(source: CommandSource): Component = render(source, "usage.stop", "/hzl-limbo stop")
    fun usageRestart(source: CommandSource): Component = render(source, "usage.restart", "/hzl-limbo restart")
    fun usageConnections(source: CommandSource): Component =
        render(source, "usage.connections", "/hzl-limbo connections")

    fun statusRunning(source: CommandSource): Component = render(source, "status.running", "NanoLimbo 后端正在运行")
    fun statusStopped(source: CommandSource): Component = render(source, "status.stopped", "NanoLimbo 后端已停止")

    fun statusAddress(source: CommandSource, address: String): Component =
        render(source, "status.address", "绑定地址: <address>", HyperZoneMessagePlaceholder.text("address", address))

    fun statusUptime(source: CommandSource, uptime: String): Component =
        render(source, "status.uptime", "运行时长: <uptime>", HyperZoneMessagePlaceholder.text("uptime", uptime))

    fun statusConnections(source: CommandSource, count: Int, maxPlayers: Int): Component = render(
        source,
        "status.connections",
        "当前连接: <count>/<max_players>",
        HyperZoneMessagePlaceholder.text("count", count),
        HyperZoneMessagePlaceholder.text("max_players", maxPlayers)
    )

    fun statusTransport(source: CommandSource, transport: String): Component =
        render(source, "status.transport", "传输类型: <transport>", HyperZoneMessagePlaceholder.text("transport", transport))

    fun statusVersion(source: CommandSource, version: String): Component =
        render(source, "status.version", "Limbo 版本: <version>", HyperZoneMessagePlaceholder.text("version", version))

    fun statusLastFailure(source: CommandSource, reason: String): Component =
        render(source, "status.last-failure", "上次启动失败: <reason>", HyperZoneMessagePlaceholder.text("reason", reason))

    fun startStarting(source: CommandSource): Component = render(source, "start.starting", "正在启动 NanoLimbo 后端...")

    fun startSuccess(source: CommandSource, address: String): Component =
        render(source, "start.success", "NanoLimbo 后端已启动: <address>", HyperZoneMessagePlaceholder.text("address", address))

    fun startFailed(source: CommandSource, reason: String): Component =
        render(source, "start.failed", "NanoLimbo 后端启动失败: <reason>", HyperZoneMessagePlaceholder.text("reason", reason))

    fun startAlreadyRunning(source: CommandSource): Component =
        render(source, "start.already-running", "NanoLimbo 后端已在运行中")

    fun stopStopping(source: CommandSource): Component = render(source, "stop.stopping", "正在停止 NanoLimbo 后端...")
    fun stopSuccess(source: CommandSource): Component = render(source, "stop.success", "NanoLimbo 后端已停止")
    fun stopNotRunning(source: CommandSource): Component = render(source, "stop.not-running", "NanoLimbo 后端本就未在运行")

    fun stopFailed(source: CommandSource, reason: String): Component =
        render(source, "stop.failed", "停止 NanoLimbo 后端失败: <reason>", HyperZoneMessagePlaceholder.text("reason", reason))

    fun restartRestarting(source: CommandSource): Component =
        render(source, "restart.restarting", "正在重启 NanoLimbo 后端...")

    fun restartSuccess(source: CommandSource, address: String): Component =
        render(source, "restart.success", "NanoLimbo 后端已重启: <address>", HyperZoneMessagePlaceholder.text("address", address))

    fun restartFailed(source: CommandSource, reason: String): Component =
        render(source, "restart.failed", "重启 NanoLimbo 后端失败: <reason>", HyperZoneMessagePlaceholder.text("reason", reason))

    fun connectionsSummary(source: CommandSource, count: Int): Component =
        render(source, "connections.summary", "当前连接数: <count>", HyperZoneMessagePlaceholder.text("count", count))

    fun connectionsEmpty(source: CommandSource): Component = render(source, "connections.empty", "当前没有玩家连接")

    fun connectionsEntry(
        source: CommandSource,
        index: Int,
        name: String,
        uuid: String,
        clientVersion: String,
        address: String,
    ): Component = render(
        source,
        "connections.entry",
        "#<index> <name> (<uuid>) <client_version> <player_address>",
        HyperZoneMessagePlaceholder.text("index", index),
        HyperZoneMessagePlaceholder.text("name", name),
        HyperZoneMessagePlaceholder.text("uuid", uuid),
        HyperZoneMessagePlaceholder.text("client_version", clientVersion),
        HyperZoneMessagePlaceholder.text("player_address", address)
    )

    fun connectionsAddressRedacted(source: CommandSource): Component =
        render(source, "connections.address-redacted", "<redacted>")

    fun busy(source: CommandSource): Component =
        render(source, "busy", "另一个后端管理操作正在执行，请稍后再试")

    /**
     * 把毫秒时长格式化为 `1h 2m 3s` 形式，未满一秒时给出 `<1s`。
     */
    fun formatDuration(millis: Long): String {
        if (millis <= 0L) {
            return "0s"
        }
        if (millis < 1000L) {
            return "<1s"
        }

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
        vararg placeholders: HyperZoneMessagePlaceholder
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
