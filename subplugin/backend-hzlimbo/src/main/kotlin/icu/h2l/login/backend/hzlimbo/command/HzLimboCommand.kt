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

package icu.h2l.login.backend.hzlimbo.command

import com.mojang.brigadier.Command
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import icu.h2l.api.log.warn
import icu.h2l.login.backend.hzlimbo.HzLimboMessages
import icu.h2l.login.backend.hzlimbo.HzLimboServer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置 HzLimbo 后端的管理命令。
 *
 * start / stop / restart 会阻塞到监听端口真正绑定或归还，因此派发到模块自己的单线程执行器上；
 * 同时用 [busy] 拒绝并发生命周期操作。
 */
class HzLimboCommand(
    private val limboServer: HzLimboServer,
) {
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "backend-hzlimbo-command").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)

    fun createCommand(): BrigadierCommand {
        return BrigadierCommand(
            BrigadierCommand.literalArgumentBuilder("hzlimbo")
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
                        },
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("start")
                        .executes { context ->
                            executeLifecycle(context.source, "start") {
                                if (limboServer.isRunning()) {
                                    HzLimboMessages.startAlreadyRunning(context.source)
                                } else {
                                    HzLimboMessages.startStarting(context.source)
                                    runCatching { limboServer.start() }.fold(
                                        onSuccess = {
                                            HzLimboMessages.startSuccess(
                                                context.source,
                                                limboServer.boundAddress()?.toString() ?: "unknown",
                                            )
                                        },
                                        onFailure = { throwable ->
                                            HzLimboMessages.startFailed(
                                                context.source,
                                                throwable.message ?: throwable.javaClass.simpleName,
                                            )
                                        },
                                    )
                                }
                            }
                            Command.SINGLE_SUCCESS
                        },
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("stop")
                        .executes { context ->
                            executeLifecycle(context.source, "stop") {
                                if (!limboServer.isRunning()) {
                                    HzLimboMessages.stopNotRunning(context.source)
                                } else {
                                    HzLimboMessages.stopStopping(context.source)
                                    runCatching { limboServer.stop() }.fold(
                                        onSuccess = { HzLimboMessages.stopSuccess(context.source) },
                                        onFailure = { throwable ->
                                            HzLimboMessages.restartFailed(
                                                context.source,
                                                throwable.message ?: throwable.javaClass.simpleName,
                                            )
                                        },
                                    )
                                }
                            }
                            Command.SINGLE_SUCCESS
                        },
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("restart")
                        .executes { context ->
                            executeLifecycle(context.source, "restart") {
                                HzLimboMessages.restartRestarting(context.source)
                                runCatching {
                                    limboServer.stop()
                                    limboServer.start()
                                }.fold(
                                    onSuccess = {
                                        HzLimboMessages.restartSuccess(
                                            context.source,
                                            limboServer.boundAddress()?.toString() ?: "unknown",
                                        )
                                    },
                                    onFailure = { throwable ->
                                        HzLimboMessages.restartFailed(
                                            context.source,
                                            throwable.message ?: throwable.javaClass.simpleName,
                                        )
                                    },
                                )
                            }
                            Command.SINGLE_SUCCESS
                        },
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("connections")
                        .executes { context ->
                            val count = limboServer.connectionCount()
                            context.source.sendMessage(HzLimboMessages.connectionsSummary(context.source, count))
                            if (count == 0) {
                                context.source.sendMessage(HzLimboMessages.connectionsEmpty(context.source))
                            }
                            Command.SINGLE_SUCCESS
                        },
                ),
        )
    }

    private fun executeLifecycle(source: CommandSource, action: String, block: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            source.sendMessage(HzLimboMessages.busy(source))
            return
        }
        lifecycleExecutor.execute {
            try {
                block()
            } catch (throwable: Throwable) {
                warn { "backend-hzlimbo 管理操作 $action 执行异常: ${throwable.message}" }
                source.sendMessage(
                    HzLimboMessages.restartFailed(source, throwable.message ?: throwable.javaClass.simpleName),
                )
            } finally {
                busy.set(false)
            }
        }
    }

    private fun showStatus(source: CommandSource) {
        val messages = mutableListOf(
            if (limboServer.isRunning()) {
                HzLimboMessages.statusRunning(source)
            } else {
                HzLimboMessages.statusStopped(source)
            },
        )

        limboServer.boundAddress()?.let { address ->
            messages += HzLimboMessages.statusAddress(source, address.toString())
        }

        if (limboServer.isRunning()) {
            messages += HzLimboMessages.statusUptime(source, HzLimboMessages.formatDuration(limboServer.uptimeMillis()))
        }

        messages += HzLimboMessages.statusConnections(source, limboServer.connectionCount())

        limboServer.lastFailure()?.let { reason ->
            messages += HzLimboMessages.statusLastFailure(source, reason)
        }

        messages.forEach(source::sendMessage)
    }

    private fun showUsage(source: CommandSource) {
        source.sendMessage(HzLimboMessages.usageStatus(source))
        source.sendMessage(HzLimboMessages.usageStart(source))
        source.sendMessage(HzLimboMessages.usageStop(source))
        source.sendMessage(HzLimboMessages.usageRestart(source))
        source.sendMessage(HzLimboMessages.usageConnections(source))
    }

    companion object {
        private const val ADMIN_PERMISSION = "hyperzonelogin.admin"
    }
}
