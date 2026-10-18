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

package ua.nanit.limbo.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.IoHandlerFactory
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.ServerChannel
import io.netty.util.ResourceLeakDetector
import ua.nanit.limbo.BuildConfig
import ua.nanit.limbo.configuration.LimboConfig
import ua.nanit.limbo.connection.ClientChannelInitializer
import ua.nanit.limbo.connection.ClientConnection
import ua.nanit.limbo.connection.PacketHandler
import ua.nanit.limbo.connection.PacketSnapshots
import ua.nanit.limbo.world.DimensionRegistry
import java.net.SocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 一条已接入等待区的客户端连接的只读快照，供管理命令展示，避免把内部连接对象暴露出去。
 */
data class LimboConnectionSnapshot(
    val username: String?,
    val uuid: UUID?,
    val clientVersion: String?,
    val address: SocketAddress?,
)

/**
 * HyperZoneLogin-owned embedded NanoLimbo server.
 *
 * This intentionally skips NanoLimbo's console command reader and JVM shutdown
 * hook because it is hosted inside Velocity and is stopped by the plugin
 * lifecycle instead.
 *
 * 生命周期由 [lifecycleLock] 串行化并保持幂等：重复 start 会抛错，重复 stop 是安全的空操作，
 * 因此管理命令可以在任意时刻调用，而不会把底层线程组或监听端口留在未定义状态。
 */
class HyperZoneLimboServer(
    val root: Path,
) : LimboServer() {
    private lateinit var config: LimboConfig
    private lateinit var packetHandler: PacketHandler
    private lateinit var connections: Connections
    private lateinit var dimensionRegistry: DimensionRegistry
    private var keepAliveTask: ScheduledFuture<*>? = null
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var serverChannel: Channel? = null
    private val commandManager: CommandManager = CommandManager()
    private val lifecycleLock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var startedAtMillis = 0L

    @Volatile
    private var lastError: String? = null

    @Throws(Exception::class)
    override fun start() {
        synchronized(lifecycleLock) {
            check(!running) { "NanoLimbo 服务已在运行中" }

            try {
                config = LimboConfig(root)
                config.load()

                Log.setLevel(config.debugLevel)
                Log.info("Starting HyperZoneLimboServer...")

                if (System.getProperty("io.netty.leakDetectionLevel") == null &&
                    System.getProperty("io.netty.leakDetection.level") == null
                ) {
                    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
                }

                packetHandler = PacketHandler(this)
                dimensionRegistry = DimensionRegistry(this)
                dimensionRegistry.load()
                connections = Connections(config)

                PacketSnapshots.initPackets(this)
                startBootstrap()

                keepAliveTask = requireNotNull(workerGroup) {
                    "workerGroup must be initialized before scheduling keep alive"
                }.scheduleAtFixedRate(::broadcastKeepAlive, 0L, 5L, TimeUnit.SECONDS)

                running = true
                startedAtMillis = System.currentTimeMillis()
                lastError = null

                Log.info("HyperZoneLimboServer started on %s", config.address)
                Log.info("NanoLimbo console command reader is disabled in embedded mode")

                System.gc()
            } catch (throwable: Throwable) {
                lastError = throwable.message ?: throwable.javaClass.simpleName
                releaseResources(true)
                running = false
                startedAtMillis = 0L
                throw throwable
            }
        }
    }

    /**
     * 停止服务。
     *
     * @return 本次调用是否真的执行了停止；服务本就未运行时返回 `false`
     */
    fun stop(): Boolean {
        synchronized(lifecycleLock) {
            if (!running && serverChannel == null && bossGroup == null && workerGroup == null) {
                return false
            }

            Log.info("Stopping HyperZoneLimboServer...")
            running = false
            startedAtMillis = 0L
            releaseResources(true)
            Log.info("HyperZoneLimboServer stopped")
            return true
        }
    }

    /**
     * 先停后启，用于让改动后的 settings.yml 生效。
     */
    fun restart() {
        stop()
        start()
    }

    fun isRunning(): Boolean = running

    fun uptimeMillis(): Long = if (running) System.currentTimeMillis() - startedAtMillis else 0L

    fun lastFailure(): String? = lastError

    fun connectionCount(): Int = if (::connections.isInitialized) connections.count else 0

    fun maxPlayers(): Int = if (::config.isInitialized) config.maxPlayers else 0

    fun transportTypeName(): String = if (::config.isInitialized) config.transportType.name else "N/A"

    fun limboVersion(): String = BuildConfig.LIMBO_VERSION

    fun boundAddress(): SocketAddress? = serverChannel?.localAddress() ?: configAddressOrNull()

    /**
     * 当前配置是否允许在管理输出中展示玩家真实地址，与 NanoLimbo 的 `logPlayersIp` 保持一致。
     */
    fun exposesPlayerAddresses(): Boolean = ::config.isInitialized && config.isLogPlayersIp

    fun connectionSnapshots(): List<LimboConnectionSnapshot> {
        if (!::connections.isInitialized) {
            return emptyList()
        }

        return connections.allConnections.map { connection ->
            LimboConnectionSnapshot(
                username = connection.username,
                uuid = connection.uuid,
                clientVersion = connection.clientVersion?.displayName,
                address = connection.address,
            )
        }
    }

    private fun configAddressOrNull(): SocketAddress? = if (::config.isInitialized) config.address else null

    private fun startBootstrap() {
        var transportType = config.transportType
        if (!transportType.isAvailable) {
            Log.debug("Transport type ${transportType.name} is not available! Using NIO.")
            transportType = TransportType.NIO
        }

        Log.debug("Using ${transportType.name} transport type")

        val channelFactory: ChannelFactory<out ServerChannel> = transportType.channelFactory
        val ioHandlerFactory: IoHandlerFactory = transportType.ioHandlerFactory

        bossGroup = MultiThreadIoEventLoopGroup(config.bossGroupSize, ioHandlerFactory)
        workerGroup = MultiThreadIoEventLoopGroup(config.workerGroupSize, ioHandlerFactory)

        // 同步等待 bind 结果：端口占用之类的错误必须在 start() 阶段抛出，
        // 否则管理命令会报告“已启动”，而实际上根本没有在监听。
        serverChannel = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channelFactory(channelFactory)
            .childHandler(ClientChannelInitializer(this))
            .childOption(ChannelOption.TCP_NODELAY, true)
            .localAddress(config.address)
            .bind()
            .syncUninterruptibly()
            .channel()
    }

    private fun broadcastKeepAlive() {
        connections.allConnections.forEach(ClientConnection::sendKeepAlive)
    }

    /**
     * 释放监听端口与事件循环线程组。等待终止是为了保证重启时端口已经真正归还给操作系统。
     */
    private fun releaseResources(awaitTermination: Boolean) {
        keepAliveTask?.cancel(true)
        keepAliveTask = null

        serverChannel?.let { channel ->
            runCatching { channel.close().syncUninterruptibly() }
        }
        serverChannel = null

        val boss = bossGroup
        bossGroup = null
        val worker = workerGroup
        workerGroup = null

        if (awaitTermination) {
            runCatching {
                boss?.shutdownGracefully(0L, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)?.syncUninterruptibly()
                worker?.shutdownGracefully(0L, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)?.syncUninterruptibly()
            }
        } else {
            boss?.shutdownGracefully()
            worker?.shutdownGracefully()
        }
    }

    override fun getConfig(): LimboConfig = config

    override fun getPacketHandler(): PacketHandler = packetHandler

    override fun getConnections(): Connections = connections

    override fun getDimensionRegistry(): DimensionRegistry = dimensionRegistry

    override fun getKeepAliveTask(): ScheduledFuture<*>? = keepAliveTask

    override fun getBossGroup(): EventLoopGroup? = bossGroup

    override fun getWorkerGroup(): EventLoopGroup? = workerGroup

    override fun getCommandManager(): CommandManager = commandManager

    companion object {
        private const val SHUTDOWN_TIMEOUT_SECONDS = 10L
    }
}
