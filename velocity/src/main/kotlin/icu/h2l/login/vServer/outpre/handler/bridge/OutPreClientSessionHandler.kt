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

package icu.h2l.login.vServer.outpre.handler.bridge

import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.connection.client.ClientConfigSessionHandler
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.protocol.MinecraftPacket
import com.velocitypowered.proxy.protocol.ProtocolUtils
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.packet.*
import com.velocitypowered.proxy.protocol.packet.chat.ChatAcknowledgementPacket
import com.velocitypowered.proxy.protocol.packet.chat.PlayerChatCompletionPacket
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerChatPacket
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerCommandPacket
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatPacket
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerChatPacket
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerCommandPacket
import com.velocitypowered.proxy.protocol.packet.chat.session.UnsignedPlayerCommandPacket
import com.velocitypowered.proxy.protocol.packet.config.CodeOfConductAcceptPacket
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket
import com.velocitypowered.proxy.protocol.packet.config.StartUpdatePacket
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedTeardown
import icu.h2l.login.manager.HyperChatCommandManagerImpl
import icu.h2l.login.vServer.outpre.OutPreBackendBridge
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

open class OutPreClientBridgeSessionHandlerLogic(
    protected val player: ConnectedPlayer,
    protected val bridge: OutPreBackendBridge,
    initialConfigMode: Boolean,
) {
    private var deferredBrandChannel: String? = null
    private var deferredBrandMessage: String? = null
    @Volatile
    private var waitingAreaCommandsSent = false
    @Volatile
    protected var releaseToVelocityCallback: (() -> Unit)? = null
    @Volatile
    protected var releaseToVelocityInProgress = false
    @Volatile
    protected var configMode: Boolean = initialConfigMode

    protected val queue = OutPreBridgePacketQueue(
        bridge = bridge,
        clientEventLoop = player.connection.eventLoop(),
        onPhaseAdvanced = { maybeSendWaitingAreaCommands(force = false) },
    )

    protected fun activeClientPhase(): OutPreBackendBridge.Phase {
        return if (configMode) OutPreBackendBridge.Phase.CONFIG else OutPreBackendBridge.Phase.PLAY_READY
    }

    // ---- 等待区命令 ----

    protected fun maybeSendWaitingAreaCommands(force: Boolean) {
        if ((!force && waitingAreaCommandsSent)
            || configMode
            || bridge.phase() != OutPreBackendBridge.Phase.PLAY_READY
            || player.protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_13)
        ) {
            return
        }
        waitingAreaCommandsSent = true
        player.connection.write(HyperChatCommandManagerImpl.createAvailableCommandsPacket(player))
        player.connection.flush()
    }

    fun refreshWaitingAreaCommands(force: Boolean = false) {
        player.connection.eventLoop().execute {
            if (force) {
                waitingAreaCommandsSent = false
            }
            maybeSendWaitingAreaCommands(force)
        }
    }

    protected fun handleWaitingAreaInput(rawInput: String): Boolean {
        HyperChatCommandManagerImpl.executeChat(player, rawInput)
        return true
    }

    // ---- 释放到 Velocity ----

    fun releaseToVelocity(server: VelocityServer, onReleased: () -> Unit) {
        player.connection.eventLoop().execute {
            if (player.protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
//                player.connection.setActiveSessionHandler(
//                    StateRegistry.PLAY,
//                    NettyReflectionHelper.createInitialConnectSessionHandler(player, server)
//                )
                player.connection.state= StateRegistry.PLAY
                onReleased()
                return@execute
            }

            if (shouldReleaseDirectlyToVelocityConfig(
                    configMode = configMode,
                    bridgeAwaitingClientConfigurationAck = bridge.isAwaitingClientConfigurationAck(),
                )
            ) {
                configMode = false
                player.connection.setActiveSessionHandler(
                    StateRegistry.CONFIG,
                    ClientConfigSessionHandler(server, player)
                )
                onReleased()
                return@execute
            }

            releaseToVelocityInProgress = true
            releaseToVelocityCallback = {
                releaseToVelocityInProgress = false
                configMode = false
                player.connection.setActiveSessionHandler(
                    StateRegistry.CONFIG,
                    ClientConfigSessionHandler(server, player)
                )
                onReleased()
            }
            player.connection.write(StartUpdatePacket.INSTANCE)
            player.connection.flush()
        }
    }

    // ---- 延迟 brand 处理 ----

    protected fun captureDeferredBrand(packet: PluginMessagePacket) {
        deferredBrandChannel = packet.channel
        deferredBrandMessage = runCatching {
            PluginMessageUtil.readBrandMessage(packet.content())
        }.getOrNull()
    }

    private fun flushDeferredBrand() {
        val brandChannel = deferredBrandChannel ?: return
        val brandMessage = deferredBrandMessage ?: return
        val brandBuf = Unpooled.buffer()
        ProtocolUtils.writeString(brandBuf, brandMessage)
        queue.sendPacket(
            requiredPhase = OutPreBackendBridge.Phase.CONFIG,
            packet = PluginMessagePacket(brandChannel, brandBuf),
        )
        deferredBrandChannel = null
        deferredBrandMessage = null
    }

    fun onBackendFinishUpdate() {
        flushDeferredBrand()
    }
}

// ---- MinecraftSessionHandler override 子类 ----

class OutPreClientBridgeSessionHandler(
    player: ConnectedPlayer,
    bridge: OutPreBackendBridge,
    configMode: Boolean,
) : OutPreClientBridgeSessionHandlerLogic(player, bridge, configMode),
    MinecraftSessionHandler {

    override fun handle(packet: PluginMessagePacket): Boolean {
        if (configMode && PluginMessageUtil.isMcBrand(packet)) {
            captureDeferredBrand(packet)
            return true
        }
        queue.sendRetained(
            requiredPhase = activeClientPhase(),
            writeNow = { it.write(packet.retain()) },
            retainForQueue = { packet.retain() },
            writer = { connection, queuedPacket -> connection.write(queuedPacket as PluginMessagePacket) },
        )
        return true
    }

    override fun handle(packet: KeepAlivePacket): Boolean {
        queue.send(activeClientPhase()) { it.write(packet) }
        return true
    }

    override fun handle(packet: ClientSettingsPacket): Boolean {
        player.setClientSettings(packet)
        queue.send(activeClientPhase()) { it.write(packet) }
        return true
    }

    override fun handle(packet: ResourcePackResponsePacket): Boolean {
        queue.send(activeClientPhase()) { it.write(packet) }
        return true
    }

    override fun handle(packet: KnownPacksPacket): Boolean {
        queue.send(activeClientPhase()) { it.write(packet) }
        return true
    }

    override fun handle(packet: ServerboundCookieResponsePacket): Boolean {
        queue.send(activeClientPhase()) { it.write(packet) }
        return true
    }

    override fun handle(packet: ServerboundCustomClickActionPacket): Boolean {
        queue.sendRetained(
            requiredPhase = activeClientPhase(),
            writeNow = { it.write(packet.retain()) },
            retainForQueue = { packet.retain() },
            writer = { connection, queuedPacket -> connection.write(queuedPacket as ServerboundCustomClickActionPacket) },
        )
        return true
    }

    override fun handle(packet: CodeOfConductAcceptPacket): Boolean {
        queue.send(activeClientPhase()) { it.write(packet) }
        return true
    }

    override fun handle(packet: PingIdentifyPacket): Boolean {
        queue.send(activeClientPhase()) { it.write(packet) }
        return true
    }

    override fun handle(packet: KeyedPlayerChatPacket): Boolean {
        return handleWaitingAreaInput(packet.message)
    }

    override fun handle(packet: SessionPlayerChatPacket): Boolean {
        return handleWaitingAreaInput(packet.message)
    }

    override fun handle(packet: KeyedPlayerCommandPacket): Boolean {
        return handleWaitingAreaInput("/${packet.command}")
    }

    override fun handle(packet: SessionPlayerCommandPacket): Boolean {
        return handleWaitingAreaInput("/${packet.command}")
    }

    override fun handle(packet: PlayerChatCompletionPacket): Boolean {
        queue.send(OutPreBackendBridge.Phase.PLAY_READY) { it.write(packet) }
        return true
    }

    override fun handle(packet: ChatAcknowledgementPacket): Boolean {
        queue.send(OutPreBackendBridge.Phase.PLAY_READY) { it.write(packet) }
        return true
    }

    override fun handle(packet: LoginPluginResponsePacket): Boolean {
        queue.sendPacket(OutPreBackendBridge.Phase.LOGIN, packet)
        return true
    }

    override fun handle(packet: FinishedUpdatePacket): Boolean {
        val releaseCallback = releaseToVelocityCallback
        if (shouldConsumeFinishedUpdateForVelocityRelease(
                releaseInProgress = releaseCallback != null && releaseToVelocityInProgress,
                configMode = configMode,
                bridgeAwaitingClientConfigurationAck = bridge.isAwaitingClientConfigurationAck(),
            )
        ) {
            releaseToVelocityCallback = null
            releaseCallback?.invoke()
            return true
        }

        if (configMode) {
            bridge.completeConfigurationFromClientAck()
            configMode = false
            player.connection.setActiveSessionHandler(StateRegistry.PLAY, this)
            maybeSendWaitingAreaCommands(force = true)
            return true
        }
        queue.send(activeClientPhase()) { it.write(packet) }
        return true
    }

    override fun handleGeneric(packet: MinecraftPacket) {
        when (packet) {
            is LegacyChatPacket -> {
                handleWaitingAreaInput(packet.message)
                return
            }

            is UnsignedPlayerCommandPacket -> {
                handleWaitingAreaInput("/${packet.command}")
                return
            }
        }
        queue.sendPacket(activeClientPhase(), packet)
    }

    override fun handleUnknown(buf: ByteBuf) {
        queue.sendRetained(
            requiredPhase = activeClientPhase(),
            writeNow = { it.write(buf.retain()) },
            retainForQueue = { buf.retain() },
            writer = { connection, queuedBuffer -> connection.write(queuedBuffer as ByteBuf) },
        )
    }

    override fun disconnected() {
        queue.clear()
        bridge.disconnect()
        runCatching {
            player.reflectedTeardown()
        }
    }
}

internal fun shouldConsumeFinishedUpdateForVelocityRelease(
    releaseInProgress: Boolean,
    configMode: Boolean,
    bridgeAwaitingClientConfigurationAck: Boolean,
): Boolean {
    return releaseInProgress && (!configMode || !bridgeAwaitingClientConfigurationAck)
}

internal fun shouldReleaseDirectlyToVelocityConfig(
    configMode: Boolean,
    bridgeAwaitingClientConfigurationAck: Boolean,
): Boolean {
    return configMode && !bridgeAwaitingClientConfigurationAck
}
