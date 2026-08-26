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

import com.velocitypowered.api.network.HandshakeIntent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults
import com.velocitypowered.proxy.network.Connections
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.packet.HandshakePacket
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket
import com.velocitypowered.proxy.server.VelocityRegisteredServer
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.reflect.VelocityInternalAccess
import icu.h2l.login.vServer.outpre.handler.bridge.OutPreBackendBridgeSessionHandler
import io.netty.channel.ChannelFutureListener
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

//封装了一个真实的 VelocityServerConnection，供 Velocity 内部字段（connectionInFlight/connectedServer）使用
class OutPreBackendBridge(
    val proxyServer: VelocityServer,
    private val authTargetAddress: InetSocketAddress,
    val player: ConnectedPlayer,
    private val registeredServer: VelocityRegisteredServer,
) {
    internal interface Callback {
        fun onJoined()
        fun onDisconnected(reason: String?)
    }

    @Volatile
    private var sessionCallback: Callback? = null

    internal fun bindSession(callback: Callback) {
        sessionCallback = callback
    }
    enum class Phase {
        IDLE,
        CONNECTING,
        LOGIN,
        CONFIG,
        PLAY_READY,
        CLOSING,
        CLOSED,
    }

    val serverConnection = VelocityServerConnection(registeredServer, null, player, proxyServer)

    var backendConnection: MinecraftConnection? = null
        private set
    private val connectFuture = CompletableFuture<ConnectionRequestResults.Impl?>()
    private val playReadyFuture = CompletableFuture<Void>()
    private val phaseListeners = CopyOnWriteArrayList<(Phase) -> Unit>()

    @Volatile
    private var bridgeSessionHandler: OutPreBackendBridgeSessionHandler? = null

    @Volatile
    // True only during 1.20.2+ config handshake after backend sent FinishedUpdate,
    // while waiting for the client FinishedUpdate ack before forwarding it back to backend.
    private var awaitingClientConfigurationAck = false

    @Volatile
    private var connectStarted = false

    @Volatile
    private var phase = Phase.IDLE

    fun targetAddress(): InetSocketAddress = authTargetAddress

    fun connect(): CompletableFuture<ConnectionRequestResults.Impl?> {
        if (connectStarted) {
            return connectFuture
        }
        connectStarted = true
        updatePhase(Phase.CONNECTING)
        proxyServer.createBootstrap(player.connection.eventLoop())
            .handler(proxyServer.backendChannelInitializer)
            .connect(authTargetAddress)
            .addListener(ChannelFutureListener { channelFuture ->
                if (!channelFuture.isSuccess) {
                    fail(channelFuture.cause() ?: IllegalStateException("OutPre backend bootstrap failed"))
                    return@ChannelFutureListener
                }

                val backendConnection = MinecraftConnection(channelFuture.channel(), proxyServer)
                this@OutPreBackendBridge.backendConnection = backendConnection
                VelocityInternalAccess.setServerConnectionConnection(serverConnection, backendConnection)
                updatePhase(Phase.LOGIN)
                backendConnection.setAssociation(serverConnection)
                channelFuture.channel().pipeline().addLast(Connections.HANDLER, backendConnection)

                if (!backendConnection.setActiveSessionHandler(StateRegistry.HANDSHAKE)) {
                    val handler = OutPreBackendBridgeSessionHandler(this)
                    bridgeSessionHandler = handler
                    backendConnection.setActiveSessionHandler(StateRegistry.HANDSHAKE, handler)
                    backendConnection.addSessionHandler(StateRegistry.LOGIN, handler)
                    backendConnection.addSessionHandler(StateRegistry.CONFIG, handler)
                    backendConnection.addSessionHandler(StateRegistry.PLAY, handler)
                }
                startHandshake(backendConnection)
            })
        return connectFuture
    }

    fun readyFuture(): CompletableFuture<Void> {
        return playReadyFuture
    }

    fun isConnected(): Boolean {
        return backendConnection?.isClosed == false
    }

    fun isAwaitingClientConfigurationAck(): Boolean {
        // Exposes "business-ready" config ack state (not just socket connected).
        return awaitingClientConfigurationAck && phase == Phase.CONFIG && backendConnection?.isClosed == false
    }

    fun phase(): Phase {
        return phase
    }

    fun addPhaseListener(listener: (Phase) -> Unit) {
        phaseListeners += listener
        listener(phase)
    }

    fun canForwardClientPackets(requiredPhase: Phase): Boolean {
        val currentPhase = phase
        if (currentPhase == Phase.CLOSING || currentPhase == Phase.CLOSED) {
            return false
        }
        if (backendConnection?.isClosed != false) {
            return false
        }
        return currentPhase.ordinal >= requiredPhase.ordinal
    }

    fun isReadyForForwarding(): Boolean {
        return phase == Phase.PLAY_READY && backendConnection?.isClosed == false
    }

    fun canQueueClientPackets(): Boolean {
        return when (phase) {
            Phase.CONNECTING, Phase.LOGIN, Phase.CONFIG -> true
            else -> false
        }
    }

    private fun startHandshake(connection: MinecraftConnection) {
        val protocolVersion: ProtocolVersion = player.protocolVersion
        val vServerConfig = HyperZoneLoginMain.getCoreConfig().vServer
        val targetAddress = targetAddress()
        val handshake = HandshakePacket()
        handshake.setIntent(HandshakeIntent.LOGIN)
        handshake.protocolVersion = protocolVersion
        handshake.serverAddress = vServerConfig.outpre.resolveOutprePresentedHost(targetAddress)
        handshake.port = vServerConfig.outpre.resolveOutprePresentedPort(targetAddress)
        connection.delayedWrite(handshake)
        connection.protocolVersion = protocolVersion
        connection.setActiveSessionHandler(StateRegistry.LOGIN)
        // 统一使用 (String, UUID) 构造器确保 holderUuid 非 null（≥1.20.2 / 26.x / 1.21.x 兼容），
        // playerKey 通过 setPlayerKey 补充，兼容 1.19-1.19.2 的 IdentifiedKey 序列化。
        val loginPacket = ServerLoginPacket(player.username, player.uniqueId)
        player.identifiedKey?.let { loginPacket.setPlayerKey(it) }
        connection.delayedWrite(loginPacket)
        connection.flush()
    }

    fun ensureConnected(): MinecraftConnection {
        return backendConnection ?: throw IllegalStateException("OutPre backend bridge is not connected")
    }

    fun isActive(): Boolean {
        return backendConnection?.isClosed == false && player.isActive
    }

    fun onBackendLoginSucceeded(usesConfigurationPhase: Boolean) {
        // Entering a new login/config round always clears stale ack state.
        awaitingClientConfigurationAck = false
        updatePhase(if (usesConfigurationPhase) Phase.CONFIG else Phase.LOGIN)
        connectFuture.complete(ConnectionRequestResults.successful(registeredServer))
    }

    fun markAwaitingClientConfigurationAck() {
        // Backend asked to finish config; now we must wait for client FinishedUpdate.
        awaitingClientConfigurationAck = true
    }

    fun completeConfigurationFromClientAck() {
        val connection = ensureConnected()
        val handler =
            bridgeSessionHandler ?: throw IllegalStateException("OutPre backend bridge handler is not initialized")
        connection.eventLoop().execute {
            if (!awaitingClientConfigurationAck || connection.isClosed) {
                return@execute
            }
            // Consume client ack once, then send FinishedUpdate to backend to complete switch.
            awaitingClientConfigurationAck = false
            connection.write(FinishedUpdatePacket.INSTANCE)
            connection.setActiveSessionHandler(StateRegistry.PLAY, handler)
        }
    }

    fun onBackendJoined() {
        updatePhase(Phase.PLAY_READY)
        playReadyFuture.complete(null)
        sessionCallback?.onJoined()
    }

    fun onBackendDisconnected(reason: String? = null) {
        fail(IllegalStateException(reason ?: "OutPre backend bridge disconnected"))
        sessionCallback?.onDisconnected(reason)
    }

    var disconnected: Boolean = false

    fun disconnect() {
        disconnected = true
        updatePhase(Phase.CLOSING)
        awaitingClientConfigurationAck = false
        if (!connectFuture.isDone) {
            connectFuture.completeExceptionally(IllegalStateException("OutPre backend bridge closed"))
        }
        if (!playReadyFuture.isDone) {
            playReadyFuture.completeExceptionally(IllegalStateException("OutPre backend bridge closed before play ready"))
        }
        serverConnection.disconnect()
        backendConnection = null
        updatePhase(Phase.CLOSED)
    }

    private fun fail(throwable: Throwable) {
        updatePhase(Phase.CLOSING)
        awaitingClientConfigurationAck = false
        connectFuture.completeExceptionally(throwable)
        playReadyFuture.completeExceptionally(throwable)
        serverConnection.disconnect()
        backendConnection = null
        updatePhase(Phase.CLOSED)
    }

    private fun updatePhase(newPhase: Phase) {
        val currentPhase = phase
        if (currentPhase == newPhase) {
            return
        }
        if (currentPhase == Phase.CLOSED) {
            return
        }
        if (currentPhase == Phase.CLOSING && newPhase != Phase.CLOSED) {
            return
        }
        if (newPhase != Phase.CLOSING && newPhase != Phase.CLOSED && newPhase.ordinal < currentPhase.ordinal) {
            return
        }
        phase = newPhase
        phaseListeners.forEach { listener ->
            runCatching {
                listener(newPhase)
            }
        }
    }
}
