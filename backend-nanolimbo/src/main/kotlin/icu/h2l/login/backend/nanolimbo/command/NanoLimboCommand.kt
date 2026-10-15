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

package icu.h2l.login.backend.nanolimbo.command

import com.mojang.brigadier.Command
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import icu.h2l.api.log.warn
import icu.h2l.login.backend.nanolimbo.NanoLimboMessages
import ua.nanit.limbo.server.HyperZoneLimboServer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置 NanoLimbo 后端的管理命令。
 *
 * 命令树：`/hzl-limbo <status|start|stop|restart|connections>`，仅对拥有
 * [ADMIN_PERMISSION] 的发送者可见（控制台默认满足）。控制台与代理内管理员共用同一入口。
 *
 * start / stop / restart 都会阻塞到监听端口真正绑定或归还为止，因此它们被派发到模块自己的
 * 单线程执行器上，避免占用代理的命令处理线程；同时用 [busy] 拒绝并发的生命周期操作，
 * 防止两个 start 同时跑进 Netty 的绑定流程。
 */
class NanoLimboCommand(
    private val limboServer: HyperZoneLimboServer,
) {
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "backend-nanolimbo-command").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)

    fun createCommand(): BrigadierCommand {
        return BrigadierCommand(
            BrigadierCommand.literalArgumentBuilder("hzl-limbo")
                .requires { source -> source.hasPermission(ADMIN_PERMISSION) }
                .executes { context ->
                    showUsage(context.source)
                    Command.SINGLE_SUCCESS
                }
                .then(
                    BrigadierCommand.literalArgumentBuilder("status")
                        .executes { context ->
                            showStatus(context.source)
                            Command.SINGLE_SUCCESS
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("start")
                        .executes { context ->
                            executeLifecycle(context.source, "start") {
                                if (limboServer.isRunning()) {
                                    NanoLimboMessages.startAlreadyRunning(context.source)
                                } else {
                                    NanoLimboMessages.startStarting(context.source)
                                    runCatching { limboServer.start() }
                                        .fold(
                                            onSuccess = {
                                                NanoLimboMessages.startSuccess(
                                                    context.source,
                                                    limboServer.boundAddress()?.toString() ?: "unknown"
                                                )
                                            },
                                            onFailure = { throwable ->
                                                NanoLimboMessages.startFailed(
                                                    context.source,
                                                    throwable.message ?: throwable.javaClass.simpleName
                                                )
                                            }
                                        )
                                }
                            }
                            Command.SINGLE_SUCCESS
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("stop")
                        .executes { context ->
                            executeLifecycle(context.source, "stop") {
                                if (!limboServer.isRunning()) {
                                    NanoLimboMessages.stopNotRunning(context.source)
                                } else {
                                    NanoLimboMessages.stopStopping(context.source)
                                    runCatching { limboServer.stop() }
                                        .fold(
                                            onSuccess = { NanoLimboMessages.stopSuccess(context.source) },
                                            onFailure = { throwable ->
                                                NanoLimboMessages.stopFailed(
                                                    context.source,
                                                    throwable.message ?: throwable.javaClass.simpleName
                                                )
                                            }
                                        )
                                }
                            }
                            Command.SINGLE_SUCCESS
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("restart")
                        .executes { context ->
                            executeLifecycle(context.source, "restart") {
                                NanoLimboMessages.restartRestarting(context.source)
                                runCatching { limboServer.restart() }
                                    .fold(
                                        onSuccess = {
                                            NanoLimboMessages.restartSuccess(
                                                context.source,
                                                limboServer.boundAddress()?.toString() ?: "unknown"
                                            )
                                        },
                                        onFailure = { throwable ->
                                            NanoLimboMessages.restartFailed(
                                                context.source,
                                                throwable.message ?: throwable.javaClass.simpleName
                                            )
                                        }
                                    )
                            }
                            Command.SINGLE_SUCCESS
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("connections")
                        .executes { context ->
                            showConnections(context.source)
                            Command.SINGLE_SUCCESS
                        }
                )
        )
    }

    /**
     * 生命周期操作串行化入口：同一时刻只允许一个 start/stop/restart 在跑。
     */
    private fun executeLifecycle(
        source: CommandSource,
        action: String,
        block: () -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) {
            source.sendMessage(NanoLimboMessages.busy(source))
            return
        }

        lifecycleExecutor.execute {
            try {
                block()
            } catch (throwable: Throwable) {
                warn { "backend-nanolimbo 管理操作 $action 执行异常: ${throwable.message}" }
                source.sendMessage(
                    NanoLimboMessages.restartFailed(source, throwable.message ?: throwable.javaClass.simpleName)
                )
            } finally {
                busy.set(false)
            }
        }
    }

    private fun showStatus(source: CommandSource) {
        val messages = mutableListOf(
            if (limboServer.isRunning()) {
                NanoLimboMessages.statusRunning(source)
            } else {
                NanoLimboMessages.statusStopped(source)
            }
        )

        limboServer.boundAddress()?.let { address ->
            messages += NanoLimboMessages.statusAddress(source, address.toString())
        }

        if (limboServer.isRunning()) {
            messages += NanoLimboMessages.statusUptime(
                source,
                NanoLimboMessages.formatDuration(limboServer.uptimeMillis())
            )
        }

        messages += NanoLimboMessages.statusConnections(source, limboServer.connectionCount(), limboServer.maxPlayers())
        messages += NanoLimboMessages.statusTransport(source, limboServer.transportTypeName())
        messages += NanoLimboMessages.statusVersion(source, limboServer.limboVersion())

        limboServer.lastFailure()?.let { reason ->
            messages += NanoLimboMessages.statusLastFailure(source, reason)
        }

        messages.forEach(source::sendMessage)
    }

    private fun showConnections(source: CommandSource) {
        val snapshots = limboServer.connectionSnapshots()
        source.sendMessage(NanoLimboMessages.connectionsSummary(source, snapshots.size))

        if (snapshots.isEmpty()) {
            source.sendMessage(NanoLimboMessages.connectionsEmpty(source))
            return
        }

        val redacted = NanoLimboMessages.connectionsAddressRedacted(source)
        val exposeAddress = limboServer.exposesPlayerAddresses()
        val hidden = redacted.toString()

        snapshots.forEachIndexed { index, snapshot ->
            source.sendMessage(
                NanoLimboMessages.connectionsEntry(
                    source = source,
                    index = index + 1,
                    name = snapshot.username ?: "unknown",
                    uuid = snapshot.uuid?.toString() ?: "unknown",
                    clientVersion = snapshot.clientVersion ?: "unknown",
                    address = if (exposeAddress) {
                        snapshot.address?.toString() ?: "unknown"
                    } else {
                        hidden
                    }
                )
            )
        }
    }

    private fun showUsage(source: CommandSource) {
        source.sendMessage(NanoLimboMessages.usageStatus(source))
        source.sendMessage(NanoLimboMessages.usageStart(source))
        source.sendMessage(NanoLimboMessages.usageStop(source))
        source.sendMessage(NanoLimboMessages.usageRestart(source))
        source.sendMessage(NanoLimboMessages.usageConnections(source))
    }

    companion object {
        private const val ADMIN_PERMISSION = "hyperzonelogin.admin"
    }
}
