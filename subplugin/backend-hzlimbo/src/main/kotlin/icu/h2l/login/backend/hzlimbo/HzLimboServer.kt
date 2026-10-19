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

import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket
import icu.h2l.login.backend.hzlimbo.config.HzLimboConfig
import icu.h2l.login.backend.hzlimbo.connection.HzChannelInitializer
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内嵌的 limbo 后端服务器，直接复用 Velocity 的网络栈（[MinecraftConnection]、StateRegistry、
 * Netty 编解码 handler），仅实现 limbo 特有的连接状态机与固定数据下发。
 *
 * 生命周期由 [lifecycleLock] 串行化并保持幂等；[start] 同步等待绑定结果，端口占用等错误会在
 * start 阶段抛出。
 */
class HzLimboServer(
    private val velocityServer: VelocityServer,
    private val bindAddress: InetSocketAddress,
    private val config: HzLimboConfig,
) {
    /** 当前模块配置，供各会话 handler 读取 ping / brand / world 设置。 */
    fun config(): HzLimboConfig = config
    /** 当前处于 play 阶段的连接，用于 keep-alive 广播与连接计数。 */
    private val connections: MutableSet<MinecraftConnection> = ConcurrentHashMap.newKeySet()

    private val entityIds = AtomicInteger(0)

    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var serverChannel: Channel? = null
    private var keepAliveTask: ScheduledFuture<*>? = null

    private val lifecycleLock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var startedAtMillis = 0L

    @Volatile
    private var lastError: String? = null

    @Throws(Exception::class)
    fun start() {
        synchronized(lifecycleLock) {
            check(!running) { "HzLimbo 后端已在运行中" }

            try {
                val boss = NioEventLoopGroup(1)
                val worker = NioEventLoopGroup()
                bossGroup = boss
                workerGroup = worker

                serverChannel = ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(HzChannelInitializer(this, velocityServer))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .localAddress(bindAddress)
                    .bind()
                    .syncUninterruptibly()
                    .channel()

                keepAliveTask = worker.scheduleAtFixedRate(
                    ::broadcastKeepAlive, KEEP_ALIVE_INTERVAL_SECONDS, KEEP_ALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS,
                )

                running = true
                startedAtMillis = System.currentTimeMillis()
                lastError = null
            } catch (throwable: Throwable) {
                lastError = throwable.message ?: throwable.javaClass.simpleName
                releaseResources()
                throw throwable
            }
        }
    }

    /** 停止服务；未运行时返回 `false`。 */
    fun stop(): Boolean {
        synchronized(lifecycleLock) {
            if (!running && bossGroup == null && workerGroup == null && serverChannel == null) {
                return false
            }
            running = false
            startedAtMillis = 0L
            connections.clear()
            releaseResources()
            return true
        }
    }

    fun isRunning(): Boolean = running

    fun uptimeMillis(): Long = if (running) System.currentTimeMillis() - startedAtMillis else 0L

    fun lastFailure(): String? = lastError

    fun connectionCount(): Int = connections.size

    fun boundAddress(): SocketAddress? = serverChannel?.localAddress()

    fun nextEntityId(): Int = entityIds.incrementAndGet()

    internal fun registerConnection(connection: MinecraftConnection) {
        connections.add(connection)
    }

    internal fun unregisterConnection(connection: MinecraftConnection) {
        connections.remove(connection)
    }

    private fun broadcastKeepAlive() {
        for (connection in connections) {
            val keepAlive = KeepAlivePacket()
            keepAlive.randomId = ThreadLocalRandom.current().nextLong()
            connection.write(keepAlive)
        }
    }

    private fun releaseResources() {
        keepAliveTask?.cancel(true)
        keepAliveTask = null

        serverChannel?.let { runCatching { it.close().syncUninterruptibly() } }
        serverChannel = null

        val boss = bossGroup
        bossGroup = null
        val worker = workerGroup
        workerGroup = null

        runCatching {
            boss?.shutdownGracefully(0L, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)?.syncUninterruptibly()
            worker?.shutdownGracefully(0L, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)?.syncUninterruptibly()
        }
    }

    companion object {
        private const val KEEP_ALIVE_INTERVAL_SECONDS = 5L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 10L
    }
}
