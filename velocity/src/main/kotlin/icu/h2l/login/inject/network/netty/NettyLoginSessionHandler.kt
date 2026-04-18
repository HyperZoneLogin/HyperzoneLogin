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

package icu.h2l.login.inject.network.netty

import com.google.common.primitives.Longs
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.proxy.crypto.IdentifiedKey
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.connection.VelocityConstants
import com.velocitypowered.proxy.connection.client.AuthSessionHandler
import com.velocitypowered.proxy.connection.client.InitialLoginSessionHandler
import com.velocitypowered.proxy.connection.client.LoginInboundConnection
import com.velocitypowered.proxy.crypto.EncryptionUtils
import com.velocitypowered.proxy.crypto.IdentifiedKeyImpl
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder
import com.velocitypowered.proxy.protocol.packet.EncryptionRequestPacket
import com.velocitypowered.proxy.protocol.packet.EncryptionResponsePacket
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket
import com.velocitypowered.proxy.util.VelocityProperties
import icu.h2l.api.event.connection.OpenPreLoginEvent
import icu.h2l.api.event.connection.OpenStartAuthEvent
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.inject.network.NettyReflectionHelper
import icu.h2l.login.inject.network.NettyReflectionHelper.fireLogin
import icu.h2l.login.inject.network.VelocityNetworkInjectorImpl
import icu.h2l.login.vServer.outpre.OutPreAuthSessionHandler
import icu.h2l.login.vServer.outpre.OutPreVServerAuth
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import net.kyori.adventure.text.Component
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.checkerframework.checker.nullness.qual.MonotonicNonNull
import java.net.InetSocketAddress
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ThreadLocalRandom

class NettyLoginSessionHandler(
    private val injector: VelocityNetworkInjectorImpl,
    private val mcConnection: MinecraftConnection,
    private val channel: Channel,
) : ChannelInboundHandlerAdapter() {
    companion object {
        val logger: Logger = LogManager.getLogger(InitialLoginSessionHandler::class.java)

        private val encryptionRequestShouldAuthenticateField by lazy {
            EncryptionRequestPacket::class.java.getDeclaredField("shouldAuthenticate").also {
                it.isAccessible = true
            }
        }
    }

    private lateinit var sessionHandler: InitialLoginSessionHandler
    private lateinit var inbound: LoginInboundConnection
    private var forceKeyAuthentication: Boolean = false
    private lateinit var login: @MonotonicNonNull ServerLoginPacket
    private var verify: ByteArray = VelocityConstants.EMPTY_BYTE_ARRAY
    private var onlineMode: Boolean = true


    override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
        initFields()

        when (msg) {
            is ServerLoginPacket -> handleServerLogin(ctx, msg)
            is EncryptionResponsePacket -> handleEncryptionResponse(ctx, msg)
            else -> super.channelRead(ctx, msg)
        }
    }

    private fun initFields() {
        if (::sessionHandler.isInitialized) {
            return
        }

        val sessionHandler = mcConnection.activeSessionHandler as? InitialLoginSessionHandler ?: return
        this.sessionHandler = sessionHandler

        this.inbound = InitialLoginSessionHandler::class.java.getDeclaredField("inbound").also {
            it.isAccessible = true
        }.get(sessionHandler) as LoginInboundConnection

        forceKeyAuthentication = VelocityProperties.readBoolean(
            "auth.forceSecureProfiles",
            injector.proxy.configuration.isForceKeyAuthentication
        )
    }

    private fun retire() {
        channel.pipeline().remove(this)
    }

    private fun generateEncryptionRequest(shouldAuthenticate: Boolean): EncryptionRequestPacket {
        val verify = ByteArray(4)
        ThreadLocalRandom.current().nextBytes(verify)

        val request = EncryptionRequestPacket()
        request.publicKey = injector.proxy.serverKeyPair.public.encoded
        request.verifyToken = verify
        encryptionRequestShouldAuthenticateField.setBoolean(request, shouldAuthenticate)
        return request
    }


    //    0-LOGIN_PACKET_EXPECTED 1-LOGIN_PACKET_RECEIVED 2-ENCRYPTION_REQUEST_SENT 3-ENCRYPTION_RESPONSE_RECEIVED
//    4-START 5-SUCCESS_SENT 6-ACKNOWLEDGED
    private var cState = 0
    private fun aState(expectedState: Int) {
        if (this.cState != expectedState) {
            if (MinecraftDecoder.DEBUG) {
                logger.error(
                    "{} Received an unexpected packet requiring state {}, but we are in {}",
                    inbound,
                    expectedState, this.cState
                )
            }
            mcConnection.close(true)
        }
    }

    private val server get() = injector.proxy

    private fun handleServerLogin(ctx: ChannelHandlerContext, packet: ServerLoginPacket) {
        aState(0)
        cState = 1
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "netty.handleServerLogin channel=${mcConnection.channel} username=${packet.username} holderUuid=${packet.holderUuid} protocol=${mcConnection.protocolVersion} sessionHandler=${mcConnection.activeSessionHandler?.javaClass?.name ?: "null"}"
        }
        val playerKey: IdentifiedKey? = packet.playerKey
        if (playerKey != null) {
            if (playerKey.hasExpired()) {
                inbound.disconnect(Component.translatable("multiplayer.disconnect.invalid_public_key_signature"))
                return
            }
            val isKeyValid: Boolean =
                if (playerKey.keyRevision == IdentifiedKey.Revision.LINKED_V2 && playerKey is IdentifiedKeyImpl) {
                    playerKey.internalAddHolder(packet.holderUuid)
                } else {
                    playerKey.isSignatureValid
                }

            if (!isKeyValid) {
                inbound.disconnect(Component.translatable("multiplayer.disconnect.invalid_public_key"))
            }
        } else if (mcConnection.protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_19)
            && forceKeyAuthentication
            && mcConnection.protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_19_3)
        ) {
            inbound.disconnect(Component.translatable("multiplayer.disconnect.missing_public_key"))
        }
        inbound.setPlayerKey(playerKey)
        this.login = packet

        val event = PreLoginEvent(inbound, login.getUsername(), login.holderUuid)
        injector.proxy.eventManager.fire(event).thenRunAsync({
            if (mcConnection.isClosed) {
                // The player was disconnected
                return@thenRunAsync
            }
            val result = event.result
            val disconnectReason = result.reasonComponent
            if (disconnectReason.isPresent) {
                // The component is guaranteed to be provided if the connection was denied.
                inbound.disconnect(disconnectReason.get())
                return@thenRunAsync
            }

            inbound.fireLogin {
                if (mcConnection.isClosed) {
                    // The player was disconnected
                    return@fireLogin
                }
                mcConnection.eventLoop().execute {

                    val holderUuid: UUID? = login.holderUuid
                    val userName: String = login.getUsername()
                    val host = if (inbound.rawVirtualHost.isPresent) inbound.rawVirtualHost.get() else ""
                    val remoteAddress = mcConnection.remoteAddress as InetSocketAddress
                    val playerIp = remoteAddress.hostString

                    val openPreLoginEvent =
                        OpenPreLoginEvent(holderUuid!!, userName, host, playerIp, mcConnection.channel)
                    injector.proxy.eventManager.fire(openPreLoginEvent).thenRun {
                        val resolvedOnlineMode = openPreLoginEvent.isOnline
                            && !result.isForceOfflineMode
                            && (injector.proxy.configuration.isOnlineMode || result.isOnlineModeAllowed)
                        debug(HyperZoneDebugType.OUTPRE_TRACE) {
                            "netty.handleServerLogin after-OpenPreLogin channel=${mcConnection.channel} username=$userName allow=${openPreLoginEvent.allow} requestedOnline=${openPreLoginEvent.isOnline} resolvedOnline=$resolvedOnlineMode forceOffline=${result.isForceOfflineMode} onlineAllowed=${result.isOnlineModeAllowed} host=$host playerIp=$playerIp"
                        }
                        if (!openPreLoginEvent.allow) {
                            inbound.disconnect(openPreLoginEvent.disconnectMessage)
                            return@thenRun
                        }
                        onlineMode = resolvedOnlineMode
                        if (mcConnection.protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
//                            高版本离线也可以加密
                            val request: EncryptionRequestPacket = generateEncryptionRequest(onlineMode)
                            this.verify = request.verifyToken.copyOf(4)
                            mcConnection.write(request)
                            cState = 2
                        } else {
//                            太低的版本不能发加密包，不然会退出
                            if (onlineMode) {
                                //低版本不用管shoudAuthenticate，没有这个参数
                                val request: EncryptionRequestPacket = generateEncryptionRequest(true)
                                this.verify = request.verifyToken.copyOf(4)
                                mcConnection.write(request)
                                cState = 2

//                            this.currentState = InitialLoginSessionHandler.LoginState.ENCRYPTION_REQUEST_SENT;
                            } else {
                                doLogin(encrypt = false, serverId = "", decryptedSharedSecret = null)
                            }
                        }

                    }.exceptionally { ex: Throwable? ->
                        logger.error("Exception in pre-login stage", ex)
                        null
                    }
                }
            }
        }, mcConnection.eventLoop()).exceptionally { ex: Throwable? ->
            logger.error("Exception in pre-login stage", ex)
            null
        }
    }

    private fun doLogin(encrypt: Boolean, serverId: String?, decryptedSharedSecret: ByteArray?) {


        val remoteAddress = mcConnection.remoteAddress as InetSocketAddress
        val playerIp = remoteAddress.hostString
        val openStartAuthEvent = OpenStartAuthEvent(
            login.getUsername(),
            login.holderUuid!!,
            serverId!!,
            playerIp,
            mcConnection.channel,
            onlineMode
        )
        val preProfile = GameProfile(login.holderUuid, login.username, Collections.emptyList())

        openStartAuthEvent.gameProfile = preProfile

        injector.proxy.eventManager.fire(openStartAuthEvent).thenRunAsync(
            {
                debug(HyperZoneDebugType.OUTPRE_TRACE) {
                    "netty.doLogin after-OpenStartAuth channel=${mcConnection.channel} username=${login.username} allow=${openStartAuthEvent.allow} onlineMode=$onlineMode gameProfile=${openStartAuthEvent.gameProfile} encrypt=$encrypt"
                }
                if (mcConnection.isClosed) {
                    // The player disconnected after we authenticated them.
                    return@thenRunAsync
                }
                // Go ahead and enable encryption. Once the client sends EncryptionResponse, encryption
                // is enabled.
                try {
                    if (encrypt) {
//                            logger.info(
//                                    "已开启加密为 {} ({})",
//                                    login.getUsername(), playerIp);
                        mcConnection.enableEncryption(decryptedSharedSecret)
                    }
                } catch (e: GeneralSecurityException) {
                    logger.error("Unable to enable encryption for connection", e)
                    // At this point, the connection is encrypted, but something's wrong on our side and
                    // we can't do anything about it.
                    mcConnection.close(true)
                    return@thenRunAsync
                }

                val getProfile = openStartAuthEvent.gameProfile

                if (!openStartAuthEvent.allow) {
                    inbound.disconnect(openStartAuthEvent.disconnectMessage)
                    return@thenRunAsync
                }

                val authSessionHandler =
                    createHandler(
                        injector.proxy, inbound, getProfile, onlineMode = onlineMode,
                        UUID.randomUUID().toString() // For LoginEvent, not important
                    )

                debug(HyperZoneDebugType.OUTPRE_TRACE) {
                    "netty.doLogin selected-handler channel=${mcConnection.channel} username=${login.username} handler=${authSessionHandler.javaClass.name} gameProfile=$getProfile"
                }

                mcConnection.setActiveSessionHandler(StateRegistry.LOGIN, authSessionHandler)

                retire()
            }, mcConnection.eventLoop()
        ).exceptionally { ex: Throwable? ->
            logger.error("Exception in login stage", ex)
            null
        }
    }

    private fun handleEncryptionResponse(ctx: ChannelHandlerContext, packet: EncryptionResponsePacket) {
        aState(2)
        cState = 3
        check(this::login.isInitialized) { "No ServerLogin packet received yet." }
        this.login

        check(verify.isNotEmpty()) { "No EncryptionRequest packet sent yet." }


        try {
            val serverKeyPair: KeyPair = server.serverKeyPair
            if (inbound.identifiedKey != null) {
                val playerKey = inbound.identifiedKey
                check(
                    playerKey!!.verifyDataSignature(
                        packet.verifyToken, verify,
                        Longs.toByteArray(packet.getSalt())
                    )
                ) { "Invalid client public signature." }
            } else {
                val decryptedVerifyToken = EncryptionUtils.decryptRsa(serverKeyPair, packet.verifyToken)
                check(
                    MessageDigest.isEqual(
                        verify,
                        decryptedVerifyToken
                    )
                ) { "Unable to successfully decrypt the verification token." }
            }

            val decryptedSharedSecret = EncryptionUtils.decryptRsa(serverKeyPair, packet.sharedSecret)
            val serverId = EncryptionUtils.generateServerId(decryptedSharedSecret, serverKeyPair.public)

            doLogin(encrypt = true, serverId = serverId, decryptedSharedSecret = decryptedSharedSecret)
        } catch (e: Throwable) {
            logger.error("认证出错", e)
            mcConnection.close(true)
        }
    }


    private fun createHandler(
        server: VelocityServer?,
        inbound: LoginInboundConnection?,
        profile: GameProfile?,
        onlineMode: Boolean,
        serverIdHash: String,
    ): MinecraftSessionHandler {
        val activeAdapter = HyperZoneLoginMain.getInstance().serverAdapter
        if (activeAdapter is OutPreVServerAuth) {
            return OutPreAuthSessionHandler(
                server = requireNotNull(server),
                inbound = requireNotNull(inbound),
                initialProfile = requireNotNull(profile),
                onlineMode = onlineMode,
                serverIdHash = serverIdHash,
                outPre = activeAdapter,
            )
        }

        return NettyReflectionHelper.createAuthSessionHandler(
            server,
            inbound,
            profile,
            onlineMode,
            serverIdHash,
        )
    }

}