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

import com.velocitypowered.api.event.player.CookieRequestEvent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.connection.PlayerDataForwarding
import com.velocitypowered.proxy.protocol.MinecraftPacket
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder
import com.velocitypowered.proxy.protocol.packet.*
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket
import icu.h2l.login.manager.HyperChatCommandManagerImpl
import icu.h2l.login.util.debugOutPreTrace
import icu.h2l.login.vServer.outpre.OutPreBackendBridge
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.util.ReferenceCountUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent

//为了保证兼容性，这里就当作普通的后端使用，不做queue等
//未 override 的包默认走 handleGeneric 转发给玩家
class OutPreBackendBridgeSessionHandler(
    private val bridge: OutPreBackendBridge,
) : MinecraftSessionHandler {

    private fun refreshWaitingAreaCommands(force: Boolean = false) {
        val clientHandler = bridge.player.connection.activeSessionHandler as? OutPreClientBridgeSessionHandler
//        第一次加入等待区
        if (clientHandler != null) {
            clientHandler.refreshWaitingAreaCommands(force)
            return
        }
//        正常游戏中

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

    private fun disconnect(reason: String?) {
        bridge.player.disconnect(Component.text(reason ?: "Disconnected by backend"))
        if (reason != null) {
//            debug用
            if (reason.contains("Outdated client!")) {
                //        Outdated client 版本不兼容，安装ViaBackwards
                debugOutPreTrace {
                    "Outdated client clientProtocol:${bridge.player.protocolVersion} serverProtocol: reason:$reason"
                }
            }
        }
        bridge.onBackendDisconnected(reason)
    }

    override fun handle(packet: EncryptionRequestPacket): Boolean {
        bridge.player.disconnect(Component.translatable("velocity.error.online-mode-only"))
        bridge.disconnect()
        return true
    }

    override fun handle(packet: LoginPluginMessagePacket): Boolean {
        val connection = bridge.ensureConnected()
        val config = bridge.proxyServer.configuration
//        CTD兼容
//        if (config.playerInfoForwardingMode == PlayerInfoForwarding.MODERN
//            && packet.channel == PlayerDataForwarding.CHANNEL
//        ) {
        if (packet.channel == PlayerDataForwarding.CHANNEL
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
        } else {
            connection.write(LoginPluginResponsePacket(packet.id, false, Unpooled.EMPTY_BUFFER))
        }
        return true
    }

    override fun handle(packet: DisconnectPacket): Boolean {
//        reson处理
        disconnect(getReason(packet.reason.component))
        return true
    }

    fun getReason(component:Component): String?{
//        如果component是Text
        if (component is TextComponent){
            return component.content()
        }else if(component is TranslatableComponent){
            return component.key()
        }
        return null
    }

    override fun handle(packet: SetCompressionPacket): Boolean {
        bridge.ensureConnected().setCompressionThreshold(packet.threshold)
        return true
    }

    override fun handle(packet: ServerLoginSuccessPacket): Boolean {
//        省略一个modern转发bug提示 CTD兼容

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

    override fun handle(packet: FinishedUpdatePacket): Boolean {
        bridge.markAwaitingClientConfigurationAck()
        (bridge.player.connection.activeSessionHandler as? OutPreClientBridgeSessionHandler)?.onBackendFinishUpdate()
        bridge.player.connection.write(FinishedUpdatePacket.INSTANCE)
        bridge.player.connection.channel.pipeline().get(MinecraftEncoder::class.java)?.setState(StateRegistry.PLAY)
        return true
    }

    override fun handle(packet: ClientboundStoreCookiePacket): Boolean {
        bridge.proxyServer.eventManager.fire(CookieRequestEvent(bridge.player, packet.key))
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
        bridge.player.connection.write(buf.retain())
    }

    override fun disconnected() {
//        这里要判断是不是正常关闭（含 Velocity 直接调 serverConnection.disconnect() 的路径）
        if (bridge.disconnected || bridge.serverConnection.connection == null) return
        disconnect("unexpected backend bridge disconnect")
    }

    override fun exception(throwable: Throwable) {
        disconnect(throwable.message)
    }
}

internal fun shouldDropOutPreBackendPacket(packet: MinecraftPacket): Boolean {
    return packet is UpsertPlayerInfoPacket
}
