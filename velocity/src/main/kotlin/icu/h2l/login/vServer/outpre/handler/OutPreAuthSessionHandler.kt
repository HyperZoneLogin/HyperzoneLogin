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
import icu.h2l.api.player.ClientLoginIdentity
import icu.h2l.login.compat.VelocityLoginCompatibilityProvider
import icu.h2l.login.inject.network.NettyReflectionHelper
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedCleanup
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedDelegatedConnection
import icu.h2l.login.inject.network.NettyReflectionHelper.reflectedTeardown
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.ProfilePropertiesApplySupport
import icu.h2l.login.reflect.VelocityInternalAccess
import icu.h2l.login.util.*
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
 * 只承载两种流程共用的登录阶段原语：
 * - 登录成功包发送；
 * - 认证后的档案 / 权限 / LoginEvent / 注册收尾；
 * - PostLogin 与初始会话切换。
 *
 * “先认证再发登录成功”还是“先发登录成功再桥接认证服”由
 * [icu.h2l.login.vServer.outpre.session.OutPreInitialJoinSession] 的具体实现决定。
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

    private var loginSuccessSent = false
    private var loginPhaseCompleted = false
    private var loginFinalized = false
    private var registeredLogin = false

    internal fun onReleased(preferredTargetServerName: String?) {
        loginState = State.RELEASED
        connectToReleasedTarget(connectedPlayer, preferredTargetServerName)
    }

    /**
     * 登录阶段中与登录成功包无关的准备工作：
     * 压缩阈值、IdentifiedKey 校验、连接关联。
     */
    protected fun prepareLoginConnection(player: ConnectedPlayer) {
        val threshold = server.configuration.compressionThreshold
        if (threshold >= 0 && mcConnection.protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_8)) {
            mcConnection.write(SetCompressionPacket(threshold))
            mcConnection.setCompressionThreshold(threshold)
        }

        validateIdentifiedKey(player, player.uniqueId)
        mcConnection.setAssociation(player)
    }

    /**
     * 发送登录成功包。具体由哪个登录流程会话决定何时调用：
     * - 等待区桥接流程：客户端一进入 outpre 阶段就发送；
     * - 阻塞认证流程：认证完成后再发送。
     *
     * 重复调用是安全的，只会真正发送一次。
     */
    internal fun sendLoginSuccess() {
        if (loginSuccessSent) {
            return
        }
        loginSuccessSent = true
        loginState = State.SUCCESS_SENT
        writeLoginSuccessPacket()

        // 1.20.2 以下没有 LoginAcknowledged，直接进入 PLAY + PostLogin。
        if (inbound.protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
            loginState = State.BRIDGING
            enterPostLoginPhase(false)
        }
    }

    internal fun isLoginPhaseCompleted(): Boolean = loginPhaseCompleted

    /**
     * net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket
     */
    private fun writeLoginSuccessPacket() {
        debugOutPreTrace {
            "outpre.handler.writeLoginSuccess channel=${mcConnection.channel.id()} player=${connectedPlayer.username} state=$loginState profile=${
                describeGameProfileBrief(
                    connectedPlayer.gameProfile
                )
            }"
        }
        // 这里不能直接读取 activeInGameProfile：
        // - 等待区桥接流程在 attach 之前就发送 success，此刻没有 IGP，客户端认领的是 loginIdentity；
        // - attach 后活动身份还可能切换，而客户端本次连接的 uuid/name 已经被第一次 success 固定。
        // loginSuccessIdentity 记录的是“本次连接实际写出的身份”，由具体流程在发送前决定，
        // 之后皮肤 tab / self skin replay 必须复用它。
        val hyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(connectedPlayer)
        val loginSuccessIdentity = hyperPlayer?.loginSuccessIdentity
            ?: ClientLoginIdentity(connectedPlayer.username, connectedPlayer.uniqueId)
        val success = ServerLoginSuccessPacket()
        success.username = loginSuccessIdentity.name
        success.properties = connectedPlayer.gameProfileProperties
        success.uuid = loginSuccessIdentity.uuid
//        26_2兼容
        if (inbound.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_26_2)) {
            success.setSessionId(UUID.randomUUID()) // use random uuid for now
        }
        mcConnection.write(success)
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

    /**
     * 认证完成后统一收尾：GameProfileRequest / 皮肤属性 / 权限 / LoginEvent / 注册。
     *
     * 只负责“档案与登录事件”，不决定何时发送登录成功或何时放行；
     * 这些由具体登录流程会话决定。
     */
    internal fun finalizeVerifiedLogin(preferredTargetServerName: String?): CompletableFuture<Void> {
        if (loginFinalized) {
            return CompletableFuture.completedFuture(null)
        }
        loginFinalized = true
        loginState = State.FINALIZING

        val completion = CompletableFuture<Void>()
        val hyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(connectedPlayer) ?: run {
            connectedPlayer.disconnect0(
                Component.text(
                    "OutPre finalization failed: missing HyperZonePlayer",
                    NamedTextColor.RED
                ), false
            )
            completion.completeExceptionally(IllegalStateException("missing HyperZonePlayer"))
            return completion
        }

        debugOutPreTrace {
            "outpre.handler.finalizeVerifiedLogin start channel=${mcConnection.channel.id()} player=${connectedPlayer.username} preferredTarget=$preferredTargetServerName attachedUser=${hyperPlayer.hasAttachedUser()} credentials=${
                hyperPlayer.getSubmittedCredentials().map { it.javaClass.simpleName }
            }"
        }

        val activeIdentity = hyperPlayer.activeInGameProfile
            ?: run {
                logger.error("OutPre finalization failed for {}: attached user identity unavailable", connectedPlayer)
                connectedPlayer.disconnect0(
                    Component.text(
                        "OutPre finalization failed: attached user identity missing",
                        NamedTextColor.RED
                    ), false
                )
                completion.completeExceptionally(IllegalStateException("attached user identity missing"))
                return completion
            }

        // 只有“登录成功包尚未发出”时才改用活动 InGameProfile 作为客户端身份。
        // 等待区桥接流程的 success 已经在 attach 前以 loginIdentity 发出；
        // 客户端自身的 uuid/name 在本次连接里已经固定，这里不能事后改成 IGP，
        // 否则 self skin replay / tab ADD_PLAYER 会和客户端已收到的 success 不一致。
        if (!loginSuccessSent) {
            hyperPlayer.markLoginSuccessIdentity(
                ClientLoginIdentity(
                    name = activeIdentity.name,
                    uuid = activeIdentity.uuid,
                )
            )
        }

        val finalCandidateProfile = buildAttachedIdentityGameProfile(
            currentGameProfile = connectedPlayer.gameProfile,
            activeIdentity = activeIdentity,
        )

        val finalProfileEvent = GameProfileRequestEvent(inbound, finalCandidateProfile, onlineMode)
        val finalization = server.eventManager.fire(finalProfileEvent).thenComposeAsync({ profileEvent ->
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

            ProfilePropertiesApplySupport.applyAsync(hyperPlayer, finalCandidateProfile)
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

                            VelocityLoginCompatibilityProvider.current.registerConnection(server, connectedPlayer).thenAcceptAsync({ registered ->
                                registeredLogin = registered
                                if (!registered) {
                                    debugOutPreTrace {
                                        "register connection failed for player {${connectedPlayer.username},maybe using Ambassador"
                                    }
                                }
                                completion.complete(null)
                            }, eventLoop)
                        }, eventLoop)
                    }, eventLoop)
                }, eventLoop)
        }, eventLoop).exceptionally { ex ->
            logger.error("Exception while finalizing outpre flow for {}", connectedPlayer, ex)
            connectedPlayer.disconnect0(Component.text("OutPre finalization failed", NamedTextColor.RED), false)
            completion.completeExceptionally(ex)
            null
        }

        finalization.whenComplete { _, throwable ->
            if (!completion.isDone) {
                completion.completeExceptionally(
                    IllegalStateException("outpre finalization ended without completion: ${throwable?.message ?: "connection closed or login rejected"}")
                )
            }
        }
        return completion
    }

    /**
     * CTD 的 finalizeLogin 需要等 PostLogin 之后执行；由具体登录流程在正确的时机调用。
     */
    internal fun finishRegisteredLogin() {
        if (!registeredLogin) {
            return
        }
        registeredLogin = false
        VelocityLoginCompatibilityProvider.current.completeRegisteredLogin(server, connectedPlayer)
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

    /**
     * 客户端完成登录阶段（1.20.2+ 收到 LoginAcknowledged，或旧版本直接进入 PLAY）后的统一收尾：
     * 切换会话 handler、触发 PostLogin，然后交给当前登录流程会话继续。
     */
    private fun enterPostLoginPhase(supportConfig: Boolean) {
        debugOutPreTrace {
            "outpre.handler.enterPostLoginPhase channel=${mcConnection.channel.id()} player=${connectedPlayer.username} supportConfig=$supportConfig"
        }
        this.supportConfig = supportConfig

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
            if (mcConnection.isClosed) {
                return@thenRunAsync
            }
            loginPhaseCompleted = true
            val session = outPre.getInitialSession(connectedPlayer)
            if (session == null) {
                logger.error("No outpre initial join session for {} after login phase", connectedPlayer)
                return@thenRunAsync
            }
            session.onClientLoginCompleted(outPre)
        }, eventLoop).exceptionally { ex ->
            logger.error("Exception while continuing outpre flow for {}", connectedPlayer, ex)
            null
        }
    }

    /**
     * 处理 1.20.2+ 客户端的 LoginAcknowledged。
     */
    internal fun completeLoginAcknowledged() {
        if (!loginSuccessSent || loginPhaseCompleted) {
            inbound.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_data"))
            return
        }
        loginState = State.BRIDGING
        enterPostLoginPhase(true)
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
            debugOutPreTrace {
                "profile addGameProfileTokensIfRequired failed, maybe using Velocity-CTD"
            }
            profile
        }
        debugOutPreTrace {
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

        prepareLoginConnection(connectedPlayer)
        if (mcConnection.isClosed) {
            return
        }
        outPre.beginInitialJoin(connectedPlayer, this)
    }

    override fun handle(packet: LoginAcknowledgedPacket): Boolean {
//        大于 1_20_2 批准了才会进入下一阶段
        completeLoginAcknowledged()
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
