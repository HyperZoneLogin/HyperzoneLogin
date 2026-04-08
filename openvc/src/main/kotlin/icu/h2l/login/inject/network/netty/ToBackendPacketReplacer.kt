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
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.skin.ProfileSkinTextures
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.inject.network.ChatSessionUpdatePacketIdResolver
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.OpenVcHyperZonePlayer
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
    companion object {
        private const val MODERN_FORWARDING_SIGNATURE_LENGTH = 32
    }

    private lateinit var mcConnection: MinecraftConnection
    private lateinit var velocityServerConnection: VelocityServerConnection
    private lateinit var player: ConnectedPlayer
    private lateinit var hyperPlayer: HyperZonePlayer

    private lateinit var config: VelocityConfiguration

    private fun replaceMessage(
        msg: Any?
    ): Any? {
        val offlinePlayer = (hyperPlayer as? OpenVcHyperZonePlayer)?.isOnlinePlayer() == false
        val loginServerTarget = isLoginServerTarget()

        if (msg is HandshakePacket || msg is ServerLoginPacket || msg is LoginPluginResponsePacket) {
            debug {
                "[ProfileSkinFlow] outbound packet=${msg.javaClass.simpleName}, replaceEnabled=${HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile}, loginServerTarget=$loginServerTarget, offlinePlayer=$offlinePlayer, killChatSession=${HyperZoneLoginMain.getMiscConfig().killChatSession}, target=${velocityServerConnection.server.serverInfo.name}, connected=${player.connectedServer?.server?.serverInfo?.name ?: "<none>"}"
            }
        }

//        离线没有这部分逻辑
//        偷吃点东西 chat_session_update "AdaptivePoolingAllocator$AdaptiveByteBuf(ridx: 0, widx: 323, cap: 323)"，
//        偷吃完可以retire
        if (HyperZoneLoginMain.getMiscConfig().killChatSession) {
            if (msg is ByteBuf) {
                val packetID = readPacketId(msg)
                packetID?.let {
                    if (ChatSessionUpdatePacketIdResolver.isChatSessionUpdate(player.protocolVersion, it)) {
//                        吃掉就结束了
                        retire("chat session update packet consumed")
                        return null
                    }
                }
                return msg
            }
        }

        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) {
            if (msg is LoginPluginResponsePacket && (!HyperZoneLoginMain.getMiscConfig().killChatSession || offlinePlayer)) {
                retire(
                    if (offlinePlayer) {
                        "offline player login plugin response passthrough"
                    } else {
                        "login plugin response handled without chat session stripping"
                    }
                )
            }
            return msg
        }

        if (msg is HandshakePacket) {
            return genHandshake()
        }
        if (msg is ServerLoginPacket) {
            val forwarded = genServerLogin()
            return forwarded
        }
        if (msg is LoginPluginResponsePacket) {
//            如果不需要吃ChatSession，或者离线玩家不会发该包，正常这里就是最后一个关键包
            if (!HyperZoneLoginMain.getMiscConfig().killChatSession || offlinePlayer)
                retire(
                    if (offlinePlayer) {
                        "offline player login plugin response handled"
                    } else {
                        "login plugin response handled without chat session stripping"
                    }
                )
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

    private fun retire(reason: String) {
        val targetServerName = if (::velocityServerConnection.isInitialized) {
            velocityServerConnection.server.serverInfo.name
        } else {
            "<uninitialized>"
        }
        val connectedServerName = if (::player.isInitialized) {
            player.connectedServer?.server?.serverInfo?.name ?: "<none>"
        } else {
            "<uninitialized>"
        }
        debug {
            "[ToBackendPacketReplacer] retire: reason=$reason, target=$targetServerName, connected=$connectedServerName, channel=$channel"
        }
        channel.pipeline().remove(this)
    }

    private fun isLoginServerTarget(): Boolean {
        val loginServerName = HyperZoneLoginMain.getBackendServerConfig().fallbackAuthServer.trim()
        if (loginServerName.isBlank()) {
            return false
        }

        val currentServerName = velocityServerConnection.server.serverInfo.name
        return currentServerName.equals(loginServerName, ignoreCase = true)
    }

    private fun genLoginPluginResponse(
        msg: LoginPluginResponsePacket
    ): LoginPluginResponsePacket {
        if (config.playerInfoForwardingMode == PlayerInfoForwarding.MODERN) {
            val buf = msg.content()
            val requestedForwardingVersion = resolveRequestedForwardingVersion(buf)
            val forwardedProfile = getGameProfile()
            debug {
                "[ProfileSkinFlow] modern forwarding payload: requestedVersion=$requestedForwardingVersion, protocol=${player.protocolVersion}, forwardingMode=${config.playerInfoForwardingMode}, profile=${describeProfile(forwardedProfile)}, identifiedKey=${player.identifiedKey != null}, target=${velocityServerConnection.server.serverInfo.name}"
            }
            val forwardingData = PlayerDataForwarding.createForwardingData(
                config.forwardingSecret,
                getPlayerRemoteAddressAsString(),
                player.protocolVersion,
                forwardedProfile,
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

    private fun resolveRequestedForwardingVersion(content: ByteBuf?): Int {
        if (content == null) {
            debug {
                "[ProfileSkinFlow] modern forwarding version missing content, fallback=${PlayerDataForwarding.MODERN_DEFAULT}, target=${velocityServerConnection.server.serverInfo.name}"
            }
            return PlayerDataForwarding.MODERN_DEFAULT
        }

        val readableBytes = content.readableBytes()
        if (readableBytes <= MODERN_FORWARDING_SIGNATURE_LENGTH) {
            debug {
                "[ProfileSkinFlow] modern forwarding version payload too short, fallback=${PlayerDataForwarding.MODERN_DEFAULT}, readableBytes=$readableBytes, target=${velocityServerConnection.server.serverInfo.name}"
            }
            return PlayerDataForwarding.MODERN_DEFAULT
        }

        val duplicate = content.duplicate()
        duplicate.skipBytes(MODERN_FORWARDING_SIGNATURE_LENGTH)
        return runCatching {
            ProtocolUtils.readVarInt(duplicate)
        }.onSuccess { actualVersion ->
            debug {
                "[ProfileSkinFlow] modern forwarding version parsed: actualVersion=$actualVersion, readableBytes=$readableBytes, target=${velocityServerConnection.server.serverInfo.name}"
            }
        }.onFailure { throwable ->
            debug {
                "[ProfileSkinFlow] modern forwarding version decode failed, fallback=${PlayerDataForwarding.MODERN_DEFAULT}, readableBytes=$readableBytes, target=${velocityServerConnection.server.serverInfo.name}, reason=${throwable.message}"
            }
        }.getOrDefault(PlayerDataForwarding.MODERN_DEFAULT)
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

        debug {
            val legacyProfileDescription = if (forwardingMode == PlayerInfoForwarding.LEGACY || forwardingMode == PlayerInfoForwarding.BUNGEEGUARD) {
                describeProfile(getGameProfile())
            } else {
                "n/a"
            }
            "[ProfileSkinFlow] handshake forwarding: mode=$forwardingMode, host=${handshake.serverAddress}, port=${handshake.port}, playerVhost=$playerVhost, legacyProfile=$legacyProfileDescription, target=${velocityServerConnection.server.serverInfo.name}"
        }

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
        val baseProfile = resolveForwardingBaseProfile()
//        这里不处理，别人看不见我们的皮肤
        debug {
            "[ProfileSkinFlow] apply start: player=${hyperPlayer.userName}, dbProfile=${hyperPlayer.getDBProfile()?.id ?: "<none>"}, baseSource=${resolveForwardingBaseProfileSource()}, base=${describeProfile(baseProfile)}, temporary=${describeProfile(hyperPlayer.getTemporaryForwardingProfile())}, initial=${describeProfile(hyperPlayer.getInitialGameProfile())}, target=${velocityServerConnection.server.serverInfo.name}"
        }
        val event = ProfileSkinApplyEvent(hyperPlayer, baseProfile)
        runCatching {
            HyperZoneLoginMain.getInstance().proxy.eventManager.fire(event).join()
        }.onSuccess {
            debug {
                "[ProfileSkinFlow] apply event completed: player=${hyperPlayer.userName}, textures=${describeTextures(event.textures)}"
            }
        }.onFailure { throwable ->
            error(throwable) { "Profile skin apply event failed: ${throwable.message}" }
        }

        val textures = event.textures ?: run {
            debug {
                "[ProfileSkinFlow] apply result: no textures available, using base profile for player=${hyperPlayer.userName}"
            }
            return baseProfile
        }
        val finalProfile = GameProfile(
            baseProfile.id,
            baseProfile.name,
            baseProfile.properties
                .filterNot { it.name.equals("textures", ignoreCase = true) }
                .toMutableList()
                .apply { add(textures.toProperty()) }
        )
        debug {
            "[ProfileSkinFlow] apply result: merged final=${describeProfile(finalProfile)}, textures=${describeTextures(textures)}"
        }
        return finalProfile
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
        val loginProfile = resolveForwardingBaseProfile()
        debug {
            "[ProfileSkinFlow] server login identity: target=${velocityServerConnection.server.serverInfo.name}, baseSource=${resolveForwardingBaseProfileSource()}, profile=${describeProfile(loginProfile)}, identifiedKey=${player.identifiedKey != null}"
        }
        if (player.identifiedKey == null
            && player.protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_19_3)
        ) {
            return ServerLoginPacket(loginProfile.name, loginProfile.id)
        } else {
            return ServerLoginPacket(
                loginProfile.name,
                player.identifiedKey
            )
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        try {
            initFields(ctx)


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

    private fun describeProfile(profile: GameProfile?): String {
        if (profile == null) {
            return "null"
        }
        val textures = profile.properties.firstOrNull { it.name.equals("textures", ignoreCase = true) }
        return "id=${profile.id}, name=${profile.name}, properties=${profile.properties.size}, textures=${describeTextures(textures)}"
    }

    private fun resolveForwardingBaseProfile(): GameProfile {
        if (isLoginServerTarget()) {
            hyperPlayer.getTemporaryForwardingProfile()?.let { temporaryProfile ->
                return temporaryProfile
            }
        }
        return hyperPlayer.getGameProfile()
    }

    private fun resolveForwardingBaseProfileSource(): String {
        return if (isLoginServerTarget() && hyperPlayer.getTemporaryForwardingProfile() != null) {
            "temporaryForwardingProfile"
        } else {
            "playerGameProfile"
        }
    }

    private fun describeTextures(textures: ProfileSkinTextures?): String {
        if (textures == null) {
            return "none"
        }
        return "present(valueLength=${textures.value.length}, signed=${textures.isSigned})"
    }

    private fun describeTextures(textures: GameProfile.Property?): String {
        if (textures == null) {
            return "none"
        }
        return "present(valueLength=${textures.value.length}, signed=${!textures.signature.isNullOrBlank()})"
    }
}