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

import com.velocitypowered.api.event.player.CookieRequestEvent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.proxy.config.PlayerInfoForwarding
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.connection.PlayerDataForwarding
import com.velocitypowered.proxy.protocol.MinecraftPacket
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder
import com.velocitypowered.proxy.protocol.packet.*
import com.velocitypowered.proxy.protocol.packet.config.*
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.login.manager.HyperChatCommandManagerImpl
import icu.h2l.login.util.debug
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.util.ReferenceCountUtil
import net.kyori.adventure.text.Component

class OutPreBackendBridgeSessionHandler(
    private val bridge: OutPreBackendBridge,
) : MinecraftSessionHandler {
    private var modernForwardingSent = false

    private fun refreshWaitingAreaCommands(force: Boolean = false) {
        val clientHandler = bridge.player.connection.activeSessionHandler as? OutPreClientBridgeSessionHandler
        if (clientHandler != null) {
            clientHandler.refreshWaitingAreaCommands(force)
            return
        }

        if (bridge.player.protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_13)) {
            return
        }

        bridge.player.connection.eventLoop().execute {
            bridge.player.connection.write(HyperChatCommandManagerImpl.createAvailableCommandsPacket(bridge.player))
            bridge.player.connection.flush()
        }
    }

    private fun forwardPacketToPlayer(packet: MinecraftPacket) {
        bridge.player.connection.write(ReferenceCountUtil.retain(packet))
    }

    private fun forwardUnknownToPlayer(buf: ByteBuf) {
        bridge.player.connection.write(buf.retain())
    }

    override fun handle(packet: EncryptionRequestPacket): Boolean {
        bridge.player.disconnect(Component.translatable("velocity.error.online-mode-only"))
        bridge.disconnect()
        return true
    }

    override fun handle(packet: LoginPluginMessagePacket): Boolean {
        val connection = bridge.ensureConnected()
        val config = bridge.proxyServer.configuration
        if (config.playerInfoForwardingMode == PlayerInfoForwarding.MODERN
            && packet.channel == PlayerDataForwarding.CHANNEL
        ) {
            var requestedForwardingVersion = PlayerDataForwarding.MODERN_DEFAULT
            if (packet.content().readableBytes() == 1) {
                requestedForwardingVersion = packet.content().readByte().toInt()
            }
            val forwardingData = PlayerDataForwarding.createForwardingData(
                config.forwardingSecret,
                bridge.player.remoteAddress.address.hostAddress,
                bridge.player.protocolVersion,
                bridge.player.gameProfile,
                bridge.player.identifiedKey,
                requestedForwardingVersion,
            )
            connection.write(LoginPluginResponsePacket(packet.id, true, forwardingData))
            modernForwardingSent = true
        } else {
            connection.write(LoginPluginResponsePacket(packet.id, false, Unpooled.EMPTY_BUFFER))
        }
        return true
    }

    override fun handle(packet: DisconnectPacket): Boolean {
        val reason = runCatching { packet.reason.component.toString() }.getOrNull()
        bridge.onBackendDisconnected(reason)
        return true
    }

    override fun handle(packet: SetCompressionPacket): Boolean {
        bridge.ensureConnected().setCompressionThreshold(packet.threshold)
        return true
    }

    override fun handle(packet: ServerLoginSuccessPacket): Boolean {
        if (bridge.proxyServer.configuration.playerInfoForwardingMode == PlayerInfoForwarding.MODERN && !modernForwardingSent) {
            debug(HyperZoneDebugType.OUTPRE_TRACE) {
                "[OutPre][${bridge.player.username}] MODERN forwarding configured but forwarding data was not sent " +
                    "(backend may not have requested it). Proceeding without forwarding."
            }
        }

        val connection = bridge.ensureConnected()
        if (connection.protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
            connection.setActiveSessionHandler(StateRegistry.PLAY, this)
            bridge.onBackendLoginSucceeded(usesConfigurationPhase = false)
        } else {
            connection.write(LoginAcknowledgedPacket())
            connection.setActiveSessionHandler(StateRegistry.CONFIG, this)
            bridge.onBackendLoginSucceeded(usesConfigurationPhase = true)
        }
        return true
    }

    override fun handle(packet: JoinGamePacket): Boolean {
        if (bridge.phase() == OutPreBackendBridge.Phase.CLOSED || bridge.phase() == OutPreBackendBridge.Phase.CLOSING) {
            return true
        }
        forwardPacketToPlayer(packet)
        bridge.onBackendJoined()
        bridge.ensureConnected().setActiveSessionHandler(StateRegistry.PLAY, this)
        refreshWaitingAreaCommands(force = true)
        return true
    }

    override fun handle(packet: StartUpdatePacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: TagsUpdatePacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: RegistrySyncPacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: ClientboundCustomReportDetailsPacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: ClientboundServerLinksPacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: CodeOfConductPacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: FinishedUpdatePacket): Boolean {
        bridge.markAwaitingClientConfigurationAck()
        (bridge.player.connection.activeSessionHandler as? OutPreClientBridgeSessionHandler)?.onBackendFinishUpdate()
        bridge.player.connection.write(FinishedUpdatePacket.INSTANCE)
        bridge.player.connection.channel.pipeline().get(MinecraftEncoder::class.java)?.setState(StateRegistry.PLAY)
        return true
    }

    override fun handle(packet: KeepAlivePacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: PluginMessagePacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: ResourcePackRequestPacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: RemoveResourcePackPacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: TransferPacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: ClientboundStoreCookiePacket): Boolean {
        bridge.proxyServer.eventManager.fire(CookieRequestEvent(bridge.player, packet.key))
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handle(packet: ClientboundCookieRequestPacket): Boolean {
        forwardPacketToPlayer(packet)
        return true
    }

    override fun handleGeneric(packet: MinecraftPacket) {
        when {
            shouldDropOutPreBackendPacket(packet) -> {
                ReferenceCountUtil.safeRelease(packet)
            }

            packet is AvailableCommandsPacket -> {
                refreshWaitingAreaCommands(force = true)
            }

            else -> forwardPacketToPlayer(packet)
        }
    }

    override fun handleUnknown(buf: ByteBuf) {
        forwardUnknownToPlayer(buf)
    }

    override fun disconnected() {
        bridge.onBackendDisconnected("unexpected backend bridge disconnect")
    }

    override fun exception(throwable: Throwable) {
        bridge.onBackendDisconnected(throwable.message)
    }
}

internal fun shouldDropOutPreBackendPacket(packet: MinecraftPacket): Boolean {
    return packet is UpsertPlayerInfoPacket
}


