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

package icu.h2l.login.inject.network

import com.velocitypowered.api.network.HandshakeIntent
import com.velocitypowered.api.permission.PermissionFunction
import com.velocitypowered.api.permission.PermissionProvider
import com.velocitypowered.api.proxy.crypto.IdentifiedKey
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection
import com.velocitypowered.proxy.connection.client.AuthSessionHandler
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.connection.client.InitialConnectSessionHandler
import com.velocitypowered.proxy.connection.client.LoginInboundConnection
import icu.h2l.login.HyperZoneLoginMain
import java.net.InetSocketAddress

private fun interface AuthSessionHandlerConstructor {
    fun create(
        server: VelocityServer?,
        inbound: LoginInboundConnection?,
        profile: GameProfile?,
        onlineMode: Boolean,
        serverIdHash: String,
    ): AuthSessionHandler
}

private fun interface ConnectedPlayerConstructor {
    fun create(
        server: VelocityServer,
        profile: GameProfile,
        inbound: LoginInboundConnection,
        onlineMode: Boolean,
    ): ConnectedPlayer
}

private fun interface InitialConnectSessionHandlerConstructor {
    fun create(player: ConnectedPlayer, server: VelocityServer): InitialConnectSessionHandler
}

@Suppress("ObjectPrivatePropertyName")
object NettyReflectionHelper {

    private val `LoginInboundConnection$fireLogin` by lazy {
        LoginInboundConnection::class.java.getDeclaredMethod("loginEventFired", Runnable::class.java)
            .also { it.isAccessible = true }
    }

    private val `LoginInboundConnection$delegatedConnection` by lazy {
        LoginInboundConnection::class.java.getDeclaredMethod("delegatedConnection")
            .also { it.isAccessible = true }
    }

    private val `LoginInboundConnection$cleanup` by lazy {
        LoginInboundConnection::class.java.getDeclaredMethod("cleanup")
            .also { it.isAccessible = true }
    }

    private val `ConnectedPlayer$teardown` by lazy {
        ConnectedPlayer::class.java.getDeclaredMethod("teardown")
            .also { it.isAccessible = true }
    }

    fun LoginInboundConnection.fireLogin(action: Runnable) {
        `LoginInboundConnection$fireLogin`.invoke(this@fireLogin, action)
    }

    fun LoginInboundConnection.reflectedDelegatedConnection(): MinecraftConnection {
        return `LoginInboundConnection$delegatedConnection`.invoke(this@reflectedDelegatedConnection) as MinecraftConnection
    }

    fun LoginInboundConnection.reflectedCleanup() {
        `LoginInboundConnection$cleanup`.invoke(this@reflectedCleanup)
    }

    fun ConnectedPlayer.reflectedTeardown() {
        `ConnectedPlayer$teardown`.invoke(this@reflectedTeardown)
    }

    private val `ConnectedPlayer$init`: ConnectedPlayerConstructor by lazy {
        val ctor = ConnectedPlayer::class.java.getDeclaredConstructor(
            VelocityServer::class.java,
            GameProfile::class.java,
            MinecraftConnection::class.java,
            InetSocketAddress::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            HandshakeIntent::class.java,
            IdentifiedKey::class.java,
        ).also { it.isAccessible = true }

        ConnectedPlayerConstructor { server, profile, inbound, onlineMode ->
            ctor.newInstance(
                server,
                profile,
                inbound.reflectedDelegatedConnection(),
                inbound.virtualHost.orElse(null),
                inbound.rawVirtualHost.orElse(null),
                onlineMode,
                inbound.handshakeIntent,
                inbound.identifiedKey,
            )
        }
    }

    private val connectedPlayerDefaultPermissionsField by lazy {
        ConnectedPlayer::class.java.getDeclaredField("DEFAULT_PERMISSIONS").also { it.isAccessible = true }
    }

    private val connectedPlayerSetPermissionFunctionMethod by lazy {
        ConnectedPlayer::class.java.getDeclaredMethod(
            "setPermissionFunction",
            PermissionFunction::class.java,
        ).also { it.isAccessible = true }
    }

    private val connectedPlayerConnectionInFlightField by lazy {
        ConnectedPlayer::class.java.getDeclaredField("connectionInFlight").also { it.isAccessible = true }
    }

    private val connectedPlayerProfileField by lazy {
        ConnectedPlayer::class.java.getDeclaredField("profile").also { it.isAccessible = true }
    }

    private val `InitialConnectSessionHandler$init`: InitialConnectSessionHandlerConstructor by lazy {
        val ctor = InitialConnectSessionHandler::class.java.getDeclaredConstructor(
            ConnectedPlayer::class.java,
            VelocityServer::class.java,
        ).also { it.isAccessible = true }

        InitialConnectSessionHandlerConstructor { player, server ->
            ctor.newInstance(player, server)
        }
    }

    private val `AuthSessionHandler$init`: AuthSessionHandlerConstructor by lazy {
        runCatching {
            val ctor = AuthSessionHandler::class.java.getDeclaredConstructor(
                VelocityServer::class.java,
                LoginInboundConnection::class.java,
                GameProfile::class.java,
                Boolean::class.javaPrimitiveType,
            ).also { it.isAccessible = true }

            AuthSessionHandlerConstructor { server: VelocityServer?,
                                            inbound: LoginInboundConnection?,
                                            profile: GameProfile?,
                                            onlineMode: Boolean,
                                            serverIdHash: String ->
                ctor.newInstance(server, inbound, profile, onlineMode)
            }
        }.recoverCatching {
            val ctor = AuthSessionHandler::class.java.getDeclaredConstructor(
                VelocityServer::class.java,
                LoginInboundConnection::class.java,
                GameProfile::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java,
            ).also { it.isAccessible = true }

            AuthSessionHandlerConstructor { server: VelocityServer?,
                                            inbound: LoginInboundConnection?,
                                            profile: GameProfile?,
                                            onlineMode: Boolean,
                                            serverIdHash: String ->
                ctor.newInstance(server, inbound, profile, onlineMode, serverIdHash)
            }
        }.getOrThrow()
    }

    fun createAuthSessionHandler(
        server: VelocityServer?,
        inbound: LoginInboundConnection?,
        profile: GameProfile?,
        onlineMode: Boolean,
        serverIdHash: String,
    ): AuthSessionHandler {
        return runCatching {
            `AuthSessionHandler$init`.create(server, inbound, profile, onlineMode, serverIdHash)
        }.getOrElse { reflectionException ->
            HyperZoneLoginMain.getInstance().logger.error(
                "反射创建 AuthSessionHandler 失败。",
                reflectionException
            )
            throw reflectionException
        }
    }

    fun createConnectedPlayer(
        server: VelocityServer,
        inbound: LoginInboundConnection,
        profile: GameProfile,
        onlineMode: Boolean,
    ): ConnectedPlayer {
        return runCatching {
            `ConnectedPlayer$init`.create(server, profile, inbound, onlineMode)
        }.getOrElse { reflectionException ->
            HyperZoneLoginMain.getInstance().logger.error(
                "反射创建 ConnectedPlayer 失败。",
                reflectionException
            )
            throw reflectionException
        }
    }

    fun defaultPermissions(): PermissionProvider {
        return connectedPlayerDefaultPermissionsField.get(null) as PermissionProvider
    }

    fun setPermissionFunction(player: ConnectedPlayer, function: PermissionFunction) {
        runCatching {
            connectedPlayerSetPermissionFunctionMethod.invoke(player, function)
        }.getOrElse { reflectionException ->
            HyperZoneLoginMain.getInstance().logger.error(
                "反射设置 ConnectedPlayer 权限函数失败。",
                reflectionException
            )
            throw reflectionException
        }
    }

    fun setConnectionInFlight(player: ConnectedPlayer, serverConnection: VelocityServerConnection?) {
        runCatching {
            connectedPlayerConnectionInFlightField.set(player, serverConnection)
        }.getOrElse { reflectionException ->
            HyperZoneLoginMain.getInstance().logger.error(
                "反射设置 ConnectedPlayer.connectionInFlight 失败。",
                reflectionException
            )
            throw reflectionException
        }
    }

    fun setGameProfile(player: ConnectedPlayer, profile: GameProfile) {
        runCatching {
            connectedPlayerProfileField.set(player, profile)
        }.getOrElse { reflectionException ->
            HyperZoneLoginMain.getInstance().logger.error(
                "反射设置 ConnectedPlayer.profile 失败。",
                reflectionException
            )
            throw reflectionException
        }
    }

    fun createInitialConnectSessionHandler(
        player: ConnectedPlayer,
        server: VelocityServer,
    ): InitialConnectSessionHandler {
        return runCatching {
            `InitialConnectSessionHandler$init`.create(player, server)
        }.getOrElse { reflectionException ->
            HyperZoneLoginMain.getInstance().logger.error(
                "反射创建 InitialConnectSessionHandler 失败。",
                reflectionException
            )
            throw reflectionException
        }
    }
}
