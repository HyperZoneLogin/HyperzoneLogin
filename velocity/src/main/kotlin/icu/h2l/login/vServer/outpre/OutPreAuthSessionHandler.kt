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
import com.velocitypowered.api.util.UuidUtils
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.config.PlayerInfoForwarding
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.connection.client.AuthSessionHandler
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.connection.client.LoginInboundConnection
import com.velocitypowered.proxy.crypto.IdentifiedKeyImpl
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.packet.LoginAcknowledgedPacket
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket
import com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket
import com.velocitypowered.proxy.protocol.packet.SetCompressionPacket
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.inject.network.NettyReflectionHelper
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedCleanup
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedDelegatedConnection
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedTeardown
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.ProfileSkinApplySupport
import icu.h2l.login.util.buildAttachedIdentityGameProfile
import icu.h2l.login.util.describeGameProfileBrief
import icu.h2l.login.util.hasSemanticGameProfileDifference
import icu.h2l.login.util.setConnectedPlayerGameProfile
import io.netty.buffer.ByteBuf
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.Objects
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * outpre 模式的登录完成处理器。
 *
 * 第一阶段只完成“客户端可进入代理 + 认证服桥接”；
 * 真正的 Velocity 注册 / GameProfileRequest / Login / PostLogin
 * 会在认证完成后再继续执行。
 */
class OutPreAuthSessionHandler(
    private val server: VelocityServer,
    private val inbound: LoginInboundConnection,
    initialProfile: GameProfile,
    private val onlineMode: Boolean,
    private val serverIdHash: String,
    private val outPre: OutPreVServerAuth,
) : MinecraftSessionHandler {
    companion object {
        private val logger: Logger = LogManager.getLogger(AuthSessionHandler::class.java)
    }

    private val mcConnection: MinecraftConnection = inbound.reflectedDelegatedConnection()
    private var profile: GameProfile = initialProfile
    private var connectedPlayer: ConnectedPlayer? = null
    private var loginState = State.START

    override fun activated() {
        profile = mcConnection.type.addGameProfileTokensIfRequired(
            profile,
            server.configuration.playerInfoForwardingMode,
        )
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "outpre.handler.activated channel=${mcConnection.channel} initialProfile=${describeGameProfileBrief(profile)} onlineMode=$onlineMode protocol=${inbound.protocolVersion}"
        }

        val player = NettyReflectionHelper.createConnectedPlayer(
            server = server,
            inbound = inbound,
            profile = profile,
            onlineMode = onlineMode,
        )
        connectedPlayer = player

        if (!server.canRegisterConnection(player)) {
            player.disconnect0(
                Component.translatable("velocity.error.already-connected-proxy", NamedTextColor.RED),
                true,
            )
            return
        }

        if (server.configuration.isLogPlayerConnections) {
            logger.info("{} entered outpre pre-registration flow", player)
        }

        startTemporaryLoginPhase(player)
    }

    private fun startTemporaryLoginPhase(player: ConnectedPlayer) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "outpre.handler.startTemporaryLoginPhase channel=${mcConnection.channel} player=${player.username} onlineMode=$onlineMode profile=${describeGameProfileBrief(player.gameProfile)}"
        }
        val threshold = server.configuration.compressionThreshold
        if (threshold >= 0 && mcConnection.protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_8)) {
            mcConnection.write(SetCompressionPacket(threshold))
            mcConnection.setCompressionThreshold(threshold)
        }

        var playerUniqueId: UUID = player.uniqueId
        if (server.configuration.playerInfoForwardingMode == PlayerInfoForwarding.NONE) {
            playerUniqueId = UuidUtils.generateOfflinePlayerUuid(player.username)
        }

        validateIdentifiedKey(player, playerUniqueId)
        mcConnection.setAssociation(player)

        val success = ServerLoginSuccessPacket()
        success.username = player.username
        success.properties = player.gameProfileProperties
        success.uuid = playerUniqueId
        mcConnection.write(success)

        loginState = State.SUCCESS_SENT
        if (inbound.protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
            loginState = State.BRIDGING
            mcConnection.setActiveSessionHandler(StateRegistry.PLAY, OutPreClientBridgeSessionHandler(player, outPre.createBridge(player), false))
            outPre.beginInitialJoin(player, this)
        }
    }

    private fun validateIdentifiedKey(player: ConnectedPlayer, playerUniqueId: UUID) {
        val playerKey: IdentifiedKey = player.identifiedKey ?: return
        if (playerKey.signatureHolder == null) {
            if (playerKey is IdentifiedKeyImpl) {
                if (!playerKey.internalAddHolder(player.uniqueId)) {
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
                "outpre.handler.completeAfterVerification ignored channel=${mcConnection.channel} state=$loginState preferredTarget=$preferredTargetServerName"
            }
            return
        }
        loginState = State.FINALIZING

        val player = connectedPlayer ?: return
        val hyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(player) ?: run {
            player.disconnect0(Component.text("OutPre finalization failed: missing HyperZonePlayer", NamedTextColor.RED), false)
            return
        }

        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "outpre.handler.completeAfterVerification start channel=${mcConnection.channel} player=${player.username} preferredTarget=$preferredTargetServerName waitingArea=${hyperPlayer.isInWaitingArea()} verified=${hyperPlayer.isVerified()} attachedProfile=${hyperPlayer.hasAttachedProfile()} credentials=${hyperPlayer.getSubmittedCredentials().map { it.javaClass.simpleName }}"
        }

        val attachedProfile = runCatching {
            HyperZoneLoginMain.getInstance().profileService.getAttachedProfile(hyperPlayer)
                ?: throw IllegalStateException("missing attached profile")
        }.getOrElse { throwable ->
            logger.error("OutPre finalization failed for {}: attached profile unavailable", player, throwable)
            player.disconnect0(Component.text("OutPre finalization failed: attached profile missing", NamedTextColor.RED), false)
            return
        }

        val finalCandidateProfile = buildAttachedIdentityGameProfile(
            currentGameProfile = player.gameProfile,
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
                    player.username,
                    describeGameProfileBrief(finalCandidateProfile),
                    describeGameProfileBrief(profileEvent.gameProfile),
                )
            }

            ProfileSkinApplySupport.applyAsync(hyperPlayer, finalCandidateProfile).thenComposeAsync({ skinResolvedProfile ->
                if (mcConnection.isClosed) {
                    return@thenComposeAsync CompletableFuture.completedFuture(null)
                }

                profile = skinResolvedProfile ?: finalCandidateProfile
                setConnectedPlayerGameProfile(player, profile)

                server.eventManager.fire(
                    PermissionsSetupEvent(player, NettyReflectionHelper.defaultPermissions())
                ).thenComposeAsync({ event ->
                    if (mcConnection.isClosed) {
                        return@thenComposeAsync CompletableFuture.completedFuture(null)
                    }

                    val function: PermissionFunction? = event.createFunction(player)
                    if (function == null) {
                        logger.error(
                            "A plugin permission provider {} provided an invalid permission function for player {}. Falling back to the default permission function.",
                            event.provider.javaClass.name,
                            player.username,
                        )
                    } else {
                        NettyReflectionHelper.setPermissionFunction(player, function)
                    }

                    server.eventManager.fire(LoginEvent(player, serverIdHash)).thenAcceptAsync({ loginEvent ->
                        if (mcConnection.isClosed) {
                            server.eventManager.fireAndForget(
                                DisconnectEvent(player, DisconnectEvent.LoginStatus.CANCELLED_BY_USER_BEFORE_COMPLETE),
                            )
                            return@thenAcceptAsync
                        }

                        val reason: Optional<Component> = loginEvent.result.reasonComponent
                        if (reason.isPresent) {
                            player.disconnect0(reason.get(), false)
                            return@thenAcceptAsync
                        }

                        if (!server.registerConnection(player)) {
                            player.disconnect0(Component.translatable("velocity.error.already-connected-proxy"), false)
                            return@thenAcceptAsync
                        }

                        continueReleasedFlow(player, preferredTargetServerName)
                    }, mcConnection.eventLoop())
                }, mcConnection.eventLoop())
            }, mcConnection.eventLoop())
        }, mcConnection.eventLoop()).exceptionally { ex ->
            logger.error("Exception while finalizing outpre flow for {}", player, ex)
            player.disconnect0(Component.text("OutPre finalization failed", NamedTextColor.RED), false)
            null
        }
    }

    private fun continueReleasedFlow(player: ConnectedPlayer, preferredTargetServerName: String?) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "outpre.handler.continueReleasedFlow channel=${mcConnection.channel} player=${player.username} preferredTarget=$preferredTargetServerName"
        }
        val releaseAction = {
            loginState = State.RELEASED
            server.eventManager.fire(PostLoginEvent(player)).thenCompose {
                connectToReleasedTarget(player, preferredTargetServerName)
            }.exceptionally { ex ->
                logger.error("Exception while continuing outpre flow for {}", player, ex)
                null
            }
            Unit
        }

        val clientHandler = mcConnection.activeSessionHandler as? OutPreClientBridgeSessionHandler
        outPre.markInitialFlowReleased(player)
        if (clientHandler == null) {
            mcConnection.setActiveSessionHandler(
                StateRegistry.PLAY,
                NettyReflectionHelper.createInitialConnectSessionHandler(player, server)
            )
            releaseAction()
            return
        }

        clientHandler.releaseToVelocity(server, releaseAction)
    }

    private fun connectToReleasedTarget(player: ConnectedPlayer, preferredTargetServerName: String?): CompletableFuture<Void> {
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
        }, player.connection.eventLoop())
    }

    override fun handle(packet: LoginAcknowledgedPacket): Boolean {
        if (loginState != State.SUCCESS_SENT) {
            inbound.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_data"))
            return true
        }

        loginState = State.BRIDGING
        val player = connectedPlayer ?: return true
        mcConnection.setActiveSessionHandler(StateRegistry.CONFIG, OutPreClientBridgeSessionHandler(player, outPre.createBridge(player), true))
        outPre.beginInitialJoin(player, this)
        return true
    }

    override fun handle(packet: ServerboundCookieResponsePacket): Boolean {
        val player = connectedPlayer ?: return true
        server.eventManager.fire(
            CookieReceiveEvent(player, packet.key, packet.payload),
        ).thenAcceptAsync({ event ->
            if (event.result.isAllowed) {
                throw IllegalStateException(
                    "A cookie was requested by a proxy plugin in login phase but the response wasn't handled",
                )
            }
        }, mcConnection.eventLoop())

        return true
    }

    override fun handleUnknown(buf: ByteBuf) {
        mcConnection.close(true)
    }

    override fun disconnected() {
        loginState = State.CLOSED
        connectedPlayer?.reflectedTeardown()
        inbound.reflectedCleanup()
    }

    private enum class State {
        START,
        SUCCESS_SENT,
        BRIDGING,
        FINALIZING,
        RELEASED,
        CLOSED,
    }
}
