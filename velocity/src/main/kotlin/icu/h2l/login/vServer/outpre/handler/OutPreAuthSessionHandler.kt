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

package icu.h2l.login.vServer.outpre.handler

import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.permission.PermissionsSetupEvent
import com.velocitypowered.api.event.player.CookieReceiveEvent
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.permission.PermissionFunction
import com.velocitypowered.api.proxy.crypto.IdentifiedKey
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.connection.client.AuthSessionHandler
import com.velocitypowered.proxy.connection.client.ClientConfigSessionHandler
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.connection.client.LoginInboundConnection
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.packet.LoginAcknowledgedPacket
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket
import com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket
import com.velocitypowered.proxy.protocol.packet.SetCompressionPacket
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.compat.VelocityLoginCompatibilityProvider
import icu.h2l.login.inject.network.NettyReflectionHelper
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedCleanup
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedDelegatedConnection
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedTeardown
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.ProfileSkinApplySupport
import icu.h2l.login.reflect.VelocityInternalAccess
import icu.h2l.login.util.buildAttachedIdentityGameProfile
import icu.h2l.login.util.describeGameProfileBrief
import icu.h2l.login.util.hasSemanticGameProfileDifference
import icu.h2l.login.util.setConnectedPlayerGameProfile
import icu.h2l.login.vServer.outpre.OutPreVServerAuth
import io.netty.buffer.ByteBuf
import io.netty.channel.EventLoop
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.*
import java.util.concurrent.CompletableFuture

private val logger: Logger = LogManager.getLogger(AuthSessionHandler::class.java)

/**
 * outpre 模式的登录完成处理器。入口点。
 *
 * 第一阶段只完成"客户端可进入代理 + 认证服桥接"；
 * 真正的 Velocity 注册 / GameProfileRequest / Login / PostLogin
 * 会在认证完成后再继续执行。
 */
open class OutPreAuthSessionHandlerLogic(
    internal val server: VelocityServer,
    protected val inbound: LoginInboundConnection,
    initialProfile: GameProfile,
    protected val onlineMode: Boolean,
    private val serverIdHash: String,
    protected val outPre: OutPreVServerAuth,
) {

    internal val mcConnection: MinecraftConnection = inbound.reflectedDelegatedConnection()
    protected var profile: GameProfile = initialProfile
    protected var loginState = State.START
    protected val connectedPlayer = NettyReflectionHelper.createConnectedPlayer(
        server = server,
        inbound = inbound,
        profile = profile,
        onlineMode = onlineMode,
    )
    internal var supportConfig: Boolean = false
    protected val eventLoop: EventLoop = mcConnection.eventLoop()

    internal fun onReleased(preferredTargetServerName: String?) {
        loginState = State.RELEASED
        connectToReleasedTarget(connectedPlayer, preferredTargetServerName)
    }

    protected fun startTemporaryLoginPhase(player: ConnectedPlayer) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "outpre.handler.startTemporaryLoginPhase channel=${mcConnection.channel.id()} player=${player.username} onlineMode=$onlineMode profile=${
                describeGameProfileBrief(
                    player.gameProfile
                )
            }"
        }
        val threshold = server.configuration.compressionThreshold
        if (threshold >= 0 && mcConnection.protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_8)) {
            mcConnection.write(SetCompressionPacket(threshold))
            mcConnection.setCompressionThreshold(threshold)
        }

//        就用我们自己的往回发，别管，这样皮肤兼容性好
//        val playerUniqueId: UUID = player.uniqueId
        val playerUniqueId: UUID = UUID.randomUUID()
//        if (server.configuration.playerInfoForwardingMode == PlayerInfoForwarding.NONE) {
//            playerUniqueId = UuidUtils.generateOfflinePlayerUuid(player.username)
//        }

        validateIdentifiedKey(player, playerUniqueId)
        mcConnection.setAssociation(player)

//        发送登入成功
//        net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket
        val success = ServerLoginSuccessPacket()
        success.username = player.username
        success.properties = player.gameProfileProperties
        success.uuid = playerUniqueId
//        26_2兼容
        if (inbound.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_26_2)) {
            success.setSessionId(UUID.randomUUID()) // use random uuid for now
        }
        mcConnection.write(success)

        loginState = State.SUCCESS_SENT
//        版本判断
        if (inbound.protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
            loginState = State.BRIDGING
            fireAndConnect(false)
        }
    }

    private fun validateIdentifiedKey(player: ConnectedPlayer, playerUniqueId: UUID) {
        val playerKey: IdentifiedKey = player.identifiedKey ?: return
        if (playerKey.signatureHolder == null) {
            if (VelocityInternalAccess.isIdentifiedKeyImpl(playerKey)) {
                if (!VelocityInternalAccess.identifiedKeyImplInternalAddHolder(playerKey, player.uniqueId)) {
                    if (onlineMode) {
                        inbound.disconnect(Component.translatable("multiplayer.disconnect.invalid_public_key"))
                    } else {
                        logger.warn("Key for player {} could not be verified!", player.username)
                    }
                }
            } else {
                logger.warn("A custom key type has been set for player {}", player.username)
            }
            return
        }

        if (!Objects.equals(playerKey.signatureHolder, playerUniqueId)) {
            logger.warn(
                "UUID for Player {} mismatches! Chat/Commands signatures will not work correctly for this player!",
                player.username,
            )
        }
    }

    fun completeAfterVerification(preferredTargetServerName: String?) {
        if (loginState != State.BRIDGING) {
            debug(HyperZoneDebugType.OUTPRE_TRACE) {
                "outpre.handler.completeAfterVerification ignored channel=${mcConnection.channel.id()} state=$loginState preferredTarget=$preferredTargetServerName"
            }
            return
        }
        loginState = State.FINALIZING

        val hyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(connectedPlayer) ?: run {
            connectedPlayer.disconnect0(
                Component.text(
                    "OutPre finalization failed: missing HyperZonePlayer",
                    NamedTextColor.RED
                ), false
            )
            return
        }

        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "outpre.handler.completeAfterVerification start channel=${mcConnection.channel.id()} player=${connectedPlayer.username} preferredTarget=$preferredTargetServerName attachedProfile=${hyperPlayer.hasAttachedProfile()} credentials=${
                hyperPlayer.getSubmittedCredentials().map { it.javaClass.simpleName }
            }"
        }

        val attachedProfile = runCatching {
            HyperZoneLoginMain.getInstance().profileService.getAttachedProfile(hyperPlayer)
                ?: throw IllegalStateException("missing attached profile")
        }.getOrElse { throwable ->
            logger.error("OutPre finalization failed for {}: attached profile unavailable", connectedPlayer, throwable)
            connectedPlayer.disconnect0(
                Component.text(
                    "OutPre finalization failed: attached profile missing",
                    NamedTextColor.RED
                ), false
            )
            return
        }

        val finalCandidateProfile = buildAttachedIdentityGameProfile(
            currentGameProfile = connectedPlayer.gameProfile,
            attachedProfile = attachedProfile,
        )

        val finalProfileEvent = GameProfileRequestEvent(inbound, finalCandidateProfile, onlineMode)
        server.eventManager.fire(finalProfileEvent).thenComposeAsync({ profileEvent ->
            if (mcConnection.isClosed) {
                return@thenComposeAsync CompletableFuture.completedFuture(null)
            }

            if (hasSemanticGameProfileDifference(finalCandidateProfile, profileEvent.gameProfile)) {
                logger.warn(
                    "GameProfileRequestEvent returned a different profile for {} during outpre finalization. The returned profile will be ignored. This may indicate another profile management plugin is installed and could cause unknown conflicts. expected=[{}], actual=[{}]",
                    connectedPlayer.username,
                    describeGameProfileBrief(finalCandidateProfile),
                    describeGameProfileBrief(profileEvent.gameProfile),
                )
            }

            ProfileSkinApplySupport.applyAsync(hyperPlayer, finalCandidateProfile)
                .thenComposeAsync({ skinResolvedProfile ->
                    if (mcConnection.isClosed) {
                        return@thenComposeAsync CompletableFuture.completedFuture(null)
                    }

                    profile = skinResolvedProfile ?: finalCandidateProfile
                    setConnectedPlayerGameProfile(connectedPlayer, profile)

                    server.eventManager.fire(
                        PermissionsSetupEvent(connectedPlayer, NettyReflectionHelper.defaultPermissions())
                    ).thenComposeAsync({ event ->
                        if (mcConnection.isClosed) {
                            return@thenComposeAsync CompletableFuture.completedFuture(null)
                        }

                        val function: PermissionFunction? = event.createFunction(connectedPlayer)
                        if (function == null) {
                            logger.error(
                                "A plugin permission provider {} provided an invalid permission function for player {}. Falling back to the default permission function.",
                                event.provider.javaClass.name,
                                connectedPlayer.username,
                            )
                        } else {
                            NettyReflectionHelper.setPermissionFunction(connectedPlayer, function)
                        }

                        server.eventManager.fire(LoginEvent(connectedPlayer, serverIdHash)).thenComposeAsync({ loginEvent ->
                            if (mcConnection.isClosed) {
                                server.eventManager.fireAndForget(
                                    DisconnectEvent(
                                        connectedPlayer,
                                        DisconnectEvent.LoginStatus.CANCELLED_BY_USER_BEFORE_COMPLETE
                                    ),
                                )
                                return@thenComposeAsync CompletableFuture.completedFuture(null)
                            }

                            val reason: Optional<Component> = loginEvent.result.reasonComponent
                            if (reason.isPresent) {
                                connectedPlayer.disconnect0(reason.get(), false)
                                return@thenComposeAsync CompletableFuture.completedFuture(null)
                            }

//                            这里register的特别晚，可以忽略
                            VelocityLoginCompatibilityProvider.current.registerConnection(server, connectedPlayer).thenAcceptAsync({ registered ->
                                if (!registered) {
//                                player.disconnect0(
//                                    Component.translatable("velocity.error.already-connected-proxy"),
//                                    false
//                                )
//                                return@thenAcceptAsync
                                    debug(HyperZoneDebugType.OUTPRE_TRACE) {
                                        "register connection failed for player {${connectedPlayer.username},maybe using Ambassador"
                                    }
                                } else {
                                    VelocityLoginCompatibilityProvider.current.completeRegisteredLogin(server, connectedPlayer)
                                }

                                continueReleasedFlow(connectedPlayer, preferredTargetServerName)
                            }, eventLoop)
                        }, eventLoop)
                    }, eventLoop)
                }, eventLoop)
        }, eventLoop).exceptionally { ex ->
            logger.error("Exception while finalizing outpre flow for {}", connectedPlayer, ex)
            connectedPlayer.disconnect0(Component.text("OutPre finalization failed", NamedTextColor.RED), false)
            null
        }
    }


    private fun continueReleasedFlow(player: ConnectedPlayer, preferredTargetServerName: String?) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "outpre.handler.continueReleasedFlow channel=${mcConnection.channel.id()} player=${player.username} preferredTarget=$preferredTargetServerName"
        }
        outPre.getInitialSession(player)?.release(outPre, this as OutPreAuthSessionHandler, preferredTargetServerName)
            ?: throw IllegalStateException("no session for player ${player.username} during release flow")
    }

    private fun connectToReleasedTarget(
        player: ConnectedPlayer,
        preferredTargetServerName: String?
    ): CompletableFuture<Void> {
        val preferredTarget = outPre.resolveReleaseTarget(player, preferredTargetServerName)
        val event = PlayerChooseInitialServerEvent(player, preferredTarget)
        return server.eventManager.fire(event).thenRunAsync({
            val toTry = event.initialServer.orElse(null)
            if (toTry == null) {
                player.disconnect0(
                    Component.translatable("velocity.error.no-available-servers", NamedTextColor.RED),
                    false,
                )
                return@thenRunAsync
            }
            player.createConnectionRequest(toTry).fireAndForget()
        }, eventLoop)
    }

    fun fireAndConnect(supportConfig: Boolean) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "fireAndConnect channel=${mcConnection.channel.id()} player=${connectedPlayer.username} supportConfig=$supportConfig"
        }
//        这里必须配置，否则会因为事件触发其他操作，导致包解析失败
        if (supportConfig) {
            mcConnection.setActiveSessionHandler(
                StateRegistry.CONFIG,
                ClientConfigSessionHandler(server, connectedPlayer)
            )
        } else {
            mcConnection.setActiveSessionHandler(
                StateRegistry.PLAY,
                VelocityInternalAccess.createInitialConnectSessionHandler(connectedPlayer, server)
            )
        }
        server.eventManager.fire(
            PostLoginEvent(connectedPlayer)
        ).thenRunAsync({
            connectBackend(supportConfig)
        }, eventLoop).exceptionally { ex ->
            logger.error("Exception while continuing outpre flow for {}", connectedPlayer, ex)
            null
        }
    }

    fun connectBackend(supportConfig: Boolean) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "connectBackend channel=${mcConnection.channel.id()} player=${connectedPlayer.username} supportConfig=$supportConfig"
        }
        this.supportConfig = supportConfig
        outPre.beginInitialJoin(connectedPlayer, this as OutPreAuthSessionHandler)
    }

    protected enum class State {
        START,
        SUCCESS_SENT,
        BRIDGING,
        FINALIZING,
        RELEASED,
        CLOSED,
    }
}

// ---- MinecraftSessionHandler override 子类 ----

class OutPreAuthSessionHandler(
    server: VelocityServer,
    inbound: LoginInboundConnection,
    initialProfile: GameProfile,
    onlineMode: Boolean,
    serverIdHash: String,
    outPre: OutPreVServerAuth,
) : OutPreAuthSessionHandlerLogic(server, inbound, initialProfile, onlineMode, serverIdHash, outPre),
    MinecraftSessionHandler {

    //    激活入口点
    override fun activated() {
//        LEGACY的话需要添加额外的 IS_FORGE_CLIENT_PROPERTY 我们先不管
        profile = try {
            mcConnection.type.addGameProfileTokensIfRequired(
                profile,
                server.configuration.playerInfoForwardingMode,
            )
        } catch (e: NoSuchMethodError) {
            debug(HyperZoneDebugType.OUTPRE_TRACE) {
                "profile addGameProfileTokensIfRequired failed, maybe using Velocity-CTD"
            }
            profile
        }
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "outpre.handler.activated channel=${mcConnection.channel.id()} initialProfile=${
                describeGameProfileBrief(
                    profile
                )
            } onlineMode=$onlineMode protocol=${inbound.protocolVersion}"
        }


//        这里不判断能不能进入，没必要，后续有自己的子服务器判断方法，主要是为了对CTD的兼容性

        if (server.configuration.isLogPlayerConnections) {
            logger.info("{} entered outpre pre-registration flow", connectedPlayer)
        }

        startTemporaryLoginPhase(connectedPlayer)
    }

    override fun handle(packet: LoginAcknowledgedPacket): Boolean {
        if (loginState != State.SUCCESS_SENT) {
            inbound.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_data"))
            return true
        }

        loginState = State.BRIDGING
//        大于 1_20_2 批准了才会进入下一阶段
        fireAndConnect(true)
        return true
    }

    override fun handle(packet: ServerboundCookieResponsePacket): Boolean {
        server.eventManager.fire(
            CookieReceiveEvent(connectedPlayer, packet.key, packet.payload),
        ).thenAcceptAsync({ event ->
            if (event.result.isAllowed) {
                throw IllegalStateException(
                    "A cookie was requested by a proxy plugin in login phase but the response wasn't handled",
                )
            }
        }, eventLoop)
        return true
    }

    override fun handleUnknown(buf: ByteBuf) {
        mcConnection.close(true)
    }

    override fun disconnected() {
        loginState = State.CLOSED
        connectedPlayer.reflectedTeardown()
        inbound.reflectedCleanup()
    }
}
