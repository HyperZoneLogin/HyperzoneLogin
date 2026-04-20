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

package icu.h2l.login.vServer.outpre

import com.google.common.collect.ImmutableList
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.messages.ChannelIdentifier
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder
import com.velocitypowered.api.proxy.server.PingOptions
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import com.velocitypowered.api.proxy.server.ServerPing
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.network.Connections
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder
import com.velocitypowered.proxy.protocol.util.ByteBufDataOutput
import com.velocitypowered.proxy.protocol.ProtocolUtils
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.handler.timeout.ReadTimeoutHandler
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.audience.ForwardingAudience
import java.lang.reflect.Constructor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 反射获取 [com.velocitypowered.proxy.server.PingSessionHandler] 的包私有构造器，延迟初始化。 */
private val pingSessionHandlerConstructor: Constructor<*> by lazy {
    Class.forName("com.velocitypowered.proxy.server.PingSessionHandler")
        .getDeclaredConstructors()
        .first()
        .also { it.isAccessible = true }
}

/**
 * OutPre 认证端点的 [RegisteredServer] 实现。
 *
 * 参照 Velocity 的 `VelocityRegisteredServer` 实现：
 * - 跟踪当前通过 OutPre 桥接连接到该端点的玩家
 * - 支持向后端服务器发送 plugin message（通过桥接连接转发）
 * - 支持 ping（直接建立新连接探测，与 VC 保持一致）
 */
class OutPreRegisteredServer(
    private val proxyServer: VelocityServer,
    private val serverInfo: ServerInfo,
) : RegisteredServer, ForwardingAudience {

    /** UUID → 当前通过该端点桥接的玩家，由 [OutPreBackendBridge] 维护 */
    private val bridgedPlayers = ConcurrentHashMap<UUID, OutPreBackendBridge>()

    // -------------------------------------------------------------------------
    // RegisteredServer
    // -------------------------------------------------------------------------

    override fun getServerInfo(): ServerInfo = serverInfo

    override fun getPlayersConnected(): Collection<Player> =
        ImmutableList.copyOf(bridgedPlayers.values.map { it.player })

    // -------------------------------------------------------------------------
    // Plugin messages — 向所有通过此端点桥接的玩家连接转发（与 VC 行为一致）
    // -------------------------------------------------------------------------

    override fun sendPluginMessage(identifier: ChannelIdentifier, data: ByteArray): Boolean =
        sendPluginMessage(identifier, Unpooled.wrappedBuffer(data))

    @Suppress("UnstableApiUsage")
    override fun sendPluginMessage(identifier: ChannelIdentifier, dataEncoder: PluginMessageEncoder): Boolean {
        val buf: ByteBuf = Unpooled.buffer()
        val output = ByteBufDataOutput(buf)
        dataEncoder.encode(output)
        return if (buf.isReadable) {
            sendPluginMessage(identifier, buf)
        } else {
            buf.release()
            false
        }
    }

    /**
     * 通过已桥接的玩家连接将 plugin message 发送到后端服务器，消息发送后释放 [data]。
     */
    fun sendPluginMessage(identifier: ChannelIdentifier, data: ByteBuf): Boolean {
        for (bridge in bridgedPlayers.values) {
            val conn = bridge.connection ?: continue
            if (conn.isClosed) continue
            conn.write(
                com.velocitypowered.proxy.protocol.packet.PluginMessagePacket(
                    identifier.id,
                    data.retainedSlice()
                )
            )
            data.release()
            return true
        }
        data.release()
        return false
    }

    // -------------------------------------------------------------------------
    // Ping — 参照 VelocityRegisteredServer#ping(EventLoop, PingOptions)
    // -------------------------------------------------------------------------

    override fun ping(): CompletableFuture<ServerPing> = ping(PingOptions.DEFAULT)

    override fun ping(pingOptions: PingOptions): CompletableFuture<ServerPing> {
        val pingFuture = CompletableFuture<ServerPing>()
        proxyServer.createBootstrap(null).handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline()
                    .addLast(Connections.FRAME_DECODER, MinecraftVarintFrameDecoder(ProtocolUtils.Direction.CLIENTBOUND))
                    .addLast(
                        Connections.READ_TIMEOUT,
                        ReadTimeoutHandler(
                            if (pingOptions.timeout == 0L) proxyServer.configuration.readTimeout.toLong()
                            else pingOptions.timeout,
                            TimeUnit.MILLISECONDS
                        )
                    )
                    .addLast(Connections.FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE)
                    .addLast(Connections.MINECRAFT_DECODER, MinecraftDecoder(ProtocolUtils.Direction.CLIENTBOUND))
                    .addLast(Connections.MINECRAFT_ENCODER, MinecraftEncoder(ProtocolUtils.Direction.SERVERBOUND))
                val conn = MinecraftConnection(ch, proxyServer)
                ch.pipeline().addLast(Connections.HANDLER, conn)
            }
        }).connect(serverInfo.address).addListener { future ->
            if (future.isSuccess) {
                val conn = (future as io.netty.channel.ChannelFuture).channel()
                    .pipeline().get(MinecraftConnection::class.java)
                val handler = pingSessionHandlerConstructor.newInstance(
                    pingFuture, this, conn,
                    pingOptions.protocolVersion,
                    pingOptions.virtualHost
                ) as com.velocitypowered.proxy.connection.MinecraftSessionHandler
                conn.setActiveSessionHandler(StateRegistry.HANDSHAKE, handler)
            } else {
                pingFuture.completeExceptionally(future.cause())
            }
        }
        return pingFuture
    }

    // -------------------------------------------------------------------------
    // Adventure ForwardingAudience — 向桥接玩家广播
    // -------------------------------------------------------------------------

    override fun audiences(): Iterable<Audience> = getPlayersConnected()

    // -------------------------------------------------------------------------
    // 桥接生命周期管理（由 OutPreBackendBridge 调用）
    // -------------------------------------------------------------------------

    internal fun registerBridge(bridge: OutPreBackendBridge) {
        bridgedPlayers[bridge.player.uniqueId] = bridge
    }

    internal fun unregisterBridge(bridge: OutPreBackendBridge) {
        bridgedPlayers.remove(bridge.player.uniqueId, bridge)
    }

    override fun toString(): String = "OutPre registered server: $serverInfo"
}







