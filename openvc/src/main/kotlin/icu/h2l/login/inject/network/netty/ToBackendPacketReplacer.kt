package icu.h2l.login.inject.network.netty

import com.velocitypowered.api.network.HandshakeIntent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.config.PlayerInfoForwarding
import com.velocitypowered.proxy.config.VelocityConfiguration
import com.velocitypowered.proxy.connection.ConnectionTypes
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.PlayerDataForwarding
import com.velocitypowered.proxy.connection.PlayerDataForwarding.createBungeeGuardForwardingAddress
import com.velocitypowered.proxy.connection.PlayerDataForwarding.createLegacyForwardingAddress
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.connection.forge.legacy.LegacyForgeConstants
import com.velocitypowered.proxy.connection.forge.modern.ModernForgeConnectionType
import com.velocitypowered.proxy.protocol.ProtocolUtils
import com.velocitypowered.proxy.protocol.packet.HandshakePacket
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket
import icu.h2l.api.event.profile.ProfileSkinApplyEvent
import icu.h2l.api.log.error
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.inject.network.ChatSessionUpdatePacketIdResolver
import icu.h2l.login.manager.HyperZonePlayerManager
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import java.util.function.Supplier

class ToBackendPacketReplacer(
    private val channel: Channel
) : ChannelOutboundHandlerAdapter() {
    private lateinit var mcConnection: MinecraftConnection
    private lateinit var velocityServerConnection: VelocityServerConnection
    private lateinit var player: ConnectedPlayer
    private lateinit var hyperPlayer: HyperZonePlayer

    private lateinit var config: VelocityConfiguration

    private fun replaceMessage(
        msg: Any?
    ): Any? {
//        偷吃点东西 chat_session_update "AdaptivePoolingAllocator$AdaptiveByteBuf(ridx: 0, widx: 323, cap: 323)"，偷吃完可以retire

        if (HyperZoneLoginMain.getMiscConfig().killChatSession) {
            if (msg is ByteBuf) {
                val packetID = readPacketId(msg)
                packetID?.let {
                    if (ChatSessionUpdatePacketIdResolver.isChatSessionUpdate(player.protocolVersion, it)) {
//                        吃掉就结束了
                        retire()
                        return null
                    }
                }
                return msg
            }
        }

        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) {
            return msg
        }
        if (msg is HandshakePacket) {
            return genHandshake()
        }
        if (msg is ServerLoginPacket) {
            return genServerLogin()
        }
        if (msg is LoginPluginResponsePacket) {
//            如果不需要吃ChatSession，正常这是最后一个包
            if (!HyperZoneLoginMain.getMiscConfig().killChatSession)
                retire()
            return genLoginPluginResponse(msg)
        }
        return msg
    }

    private fun readPacketId(msg: ByteBuf): Int? {
        if (!msg.isReadable) {
            return null
        }

        val duplicate = msg.duplicate()
        return runCatching {
            ProtocolUtils.readVarInt(duplicate)
        }.getOrNull()
    }

    private fun retire() {
        channel.pipeline().remove(this)
    }

    private fun shouldBypassForLoginServer(): Boolean {
        val loginServerName = HyperZoneLoginMain.getMiscConfig().fallbackAuthServer.trim()
        if (loginServerName.isBlank()) {
            return false
        }

        val connectedServerName = player.connectedServer
            ?.server
            ?.serverInfo
            ?.name
        if (connectedServerName.equals(loginServerName, ignoreCase = true)) {
            return true
        }

        val targetServerName = velocityServerConnection.server.serverInfo.name
        return targetServerName.equals(loginServerName, ignoreCase = true)
    }

    private fun genLoginPluginResponse(
        msg: LoginPluginResponsePacket
    ): LoginPluginResponsePacket {
        if (config.playerInfoForwardingMode == PlayerInfoForwarding.MODERN) {
            val buf = msg.content()
            var requestedForwardingVersion = PlayerDataForwarding.MODERN_DEFAULT
            // Check version
            if (buf.readableBytes() >= 1) {
                requestedForwardingVersion = ProtocolUtils.readVarInt(buf)
            }
            val forwardingData = PlayerDataForwarding.createForwardingData(
                config.forwardingSecret,
                getPlayerRemoteAddressAsString(),
                player.protocolVersion,
                getGameProfile(),
                player.identifiedKey,
                requestedForwardingVersion
            )

            val response = LoginPluginResponsePacket(
                msg.id, true, forwardingData
            )
            return response
        }

        return msg

    }

    private lateinit var fillAddr: InetSocketAddress

    private fun genHandshake(): HandshakePacket {
        val forwardingMode: PlayerInfoForwarding? = config.playerInfoForwardingMode
//        val player = HyperZonePlayerManager.getByChannel(ctx.channel()).proxyPlayer!! as ConnectedPlayer


        // Initiate the handshake.
        val protocolVersion: ProtocolVersion? = player.connection.protocolVersion
        val playerVhost: String? = player.virtualHost
            .orElseGet { fillAddr }
            .hostString

        val handshake = HandshakePacket()
        handshake.setIntent(HandshakeIntent.LOGIN)
        handshake.protocolVersion = protocolVersion
        if (forwardingMode == PlayerInfoForwarding.LEGACY) {
            handshake.serverAddress = createLegacyForwardingAddress()
        } else if (forwardingMode == PlayerInfoForwarding.BUNGEEGUARD) {
            val secret: ByteArray = config.forwardingSecret
            handshake.serverAddress = createBungeeGuardForwardingAddress(secret)
        } else if (player.connection.type === ConnectionTypes.LEGACY_FORGE) {
            handshake.serverAddress = playerVhost + LegacyForgeConstants.HANDSHAKE_HOSTNAME_TOKEN
        } else if (player.connection.type is ModernForgeConnectionType) {
            handshake.serverAddress = playerVhost + (player
                .connection.type as ModernForgeConnectionType).getModernToken()
        } else {
            handshake.serverAddress = playerVhost
        }

        handshake.port = player.virtualHost
            .orElseGet(Supplier { fillAddr })
            .port

        return handshake
    }

    private fun createLegacyForwardingAddress(): String {
        return createLegacyForwardingAddress(
            player.virtualHost.orElseGet(Supplier { fillAddr })
                .hostString,
            getPlayerRemoteAddressAsString(),
            getGameProfile()
        )
    }

    private fun createBungeeGuardForwardingAddress(forwardingSecret: ByteArray): String {
        return createBungeeGuardForwardingAddress(
            player.virtualHost.orElseGet(Supplier { fillAddr })
                .hostString,
            getPlayerRemoteAddressAsString(),
            getGameProfile(),
            forwardingSecret
        )
    }

    fun getGameProfile(): GameProfile {
        val baseProfile = hyperPlayer.getGameProfile()
        val event = ProfileSkinApplyEvent(hyperPlayer, baseProfile)
        runCatching {
            HyperZoneLoginMain.getInstance().proxy.eventManager.fire(event).join()
        }.onFailure { throwable ->
            error(throwable) { "Profile skin apply event failed: ${throwable.message}" }
        }

        val textures = event.textures ?: return baseProfile
        return GameProfile(
            baseProfile.id,
            baseProfile.name,
            baseProfile.properties
                .filterNot { it.name.equals("textures", ignoreCase = true) }
                .toMutableList()
                .apply { add(textures.toProperty()) }
        )
    }

    fun getPlayerRemoteAddressAsString(): String {
        val addr: String = player.remoteAddress.address.hostAddress
        val ipv6ScopeIdx = addr.indexOf('%')
        if (ipv6ScopeIdx == -1) {
            return addr
        } else {
            return addr.substring(0, ipv6ScopeIdx)
        }
    }


    private fun genServerLogin(): ServerLoginPacket {
        if (player.identifiedKey == null
            && player.protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_19_3)
        ) {
            return (ServerLoginPacket(hyperPlayer.userName, hyperPlayer.uuid))
        } else {
            return ServerLoginPacket(
                hyperPlayer.userName,
                player.identifiedKey
            )
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        try {
            initFields(ctx)

            if (shouldBypassForLoginServer()) {
                retire()
                super.write(ctx, msg, promise)
                return
            }

//        println("W: $msg")
            val getMsg = replaceMessage(msg)
            if (getMsg == null) {
                ReferenceCountUtil.safeRelease(msg)
                promise?.setSuccess()
                return
            }
            super.write(ctx, getMsg, promise)
        } catch (t: Throwable) {
            // Log via the global API logger bridge so it's consistent with the rest of the project
            error(t) { "ToBackendPacketReplacer write failed: ${t.message}" }
            // Propagate to Netty's exception handling to avoid silently swallowing
            try {
                ctx.fireExceptionCaught(t)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun initFields(ctx: ChannelHandlerContext) {
        if (::mcConnection.isInitialized) {
            return
        }
        val conn = ctx.channel().pipeline().get(MinecraftConnection::class.java) ?: return

        this.mcConnection = conn
        this.velocityServerConnection = conn.association as VelocityServerConnection
        this.player = velocityServerConnection.player

        this.fillAddr = velocityServerConnection.server.serverInfo.address
        this.hyperPlayer = HyperZonePlayerManager.getByPlayer(player)
        val server = HyperZoneLoginMain.getInstance().proxy
        config = (server.configuration as VelocityConfiguration)
    }
}