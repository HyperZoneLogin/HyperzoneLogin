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
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.IoHandlerFactory
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.ServerChannel
import io.netty.util.ResourceLeakDetector
import ua.nanit.limbo.configuration.LimboConfig
import ua.nanit.limbo.connection.ClientChannelInitializer
import ua.nanit.limbo.connection.ClientConnection
import ua.nanit.limbo.connection.PacketHandler
import ua.nanit.limbo.connection.PacketSnapshots
import ua.nanit.limbo.world.DimensionRegistry
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * HyperZoneLogin-owned embedded NanoLimbo server.
 *
 * This intentionally skips NanoLimbo's console command reader and JVM shutdown
 * hook because it is hosted inside Velocity and is stopped by the plugin
 * lifecycle instead.
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
    private val commandManager: CommandManager = CommandManager()

    @Throws(Exception::class)
    override fun start() {
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

        Log.info("HyperZoneLimboServer started on %s", config.address)
        Log.info("NanoLimbo console command reader is disabled in embedded mode")

        System.gc()
    }

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

        ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channelFactory(channelFactory)
            .childHandler(ClientChannelInitializer(this))
            .childOption(ChannelOption.TCP_NODELAY, true)
            .localAddress(config.address)
            .bind()
    }

    private fun broadcastKeepAlive() {
        connections.allConnections.forEach(ClientConnection::sendKeepAlive)
    }

    fun stop() {
        Log.info("Stopping HyperZoneLimboServer...")

        keepAliveTask?.cancel(true)
        keepAliveTask = null

        bossGroup?.shutdownGracefully()
        bossGroup = null

        workerGroup?.shutdownGracefully()
        workerGroup = null

        Log.info("HyperZoneLimboServer stopped")
    }

    override fun getConfig(): LimboConfig = config

    override fun getPacketHandler(): PacketHandler = packetHandler

    override fun getConnections(): Connections = connections

    override fun getDimensionRegistry(): DimensionRegistry = dimensionRegistry

    override fun getKeepAliveTask(): ScheduledFuture<*>? = keepAliveTask

    override fun getBossGroup(): EventLoopGroup? = bossGroup

    override fun getWorkerGroup(): EventLoopGroup? = workerGroup

    override fun getCommandManager(): CommandManager = commandManager
}

