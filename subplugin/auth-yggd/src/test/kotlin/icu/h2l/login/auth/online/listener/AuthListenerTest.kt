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

package icu.h2l.login.auth.online.listener

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.event.auth.LoginHandleResult
import icu.h2l.api.event.auth.LoginHandleSession
import icu.h2l.api.event.connection.OpenStartAuthEvent
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.login.auth.online.iface.YggdrasilAuthFlow
import io.netty.channel.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.*
import java.util.concurrent.CompletableFuture

class AuthListenerTest {

    @Test
    fun `online auth starts immediately and waiting area join only registers`() {
        val authFlow = RecordingAuthFlow()
        val listener = AuthListener(authFlow)
        val channel = interfaceStub(Channel::class.java)
        val player = interfaceStub(
            Player::class.java,
            mapOf(
                "getUsername" to "YggPlayer",
                "isOnlineMode" to true
            )
        )
        val hyperZonePlayer = interfaceStub(
            HyperZonePlayer::class.java,
            mapOf("hasAttachedProfile" to false)
        )
        val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

        listener.onOnlineAuth(OpenStartAuthEvent("YggPlayer", uuid, "server-id", "127.0.0.1", channel, true))
        listener.onWaitingAreaJoin(channel, player, hyperZonePlayer)

        assertEquals(1, authFlow.startCalls.size)
        assertSame(channel, authFlow.startCalls.single().channel)
        assertEquals("YggPlayer", authFlow.startCalls.single().username)
        assertEquals(uuid, authFlow.startCalls.single().uuid)
        assertEquals("server-id", authFlow.startCalls.single().serverId)
        assertEquals("127.0.0.1", authFlow.startCalls.single().playerIp)
        assertEquals(1, authFlow.registerCalls.size)
        assertSame(player, authFlow.registerCalls.single().player)
        assertSame(hyperZonePlayer, authFlow.registerCalls.single().hyperZonePlayer)
    }

    @Test
    fun `offline auth does not start`() {
        val authFlow = RecordingAuthFlow()
        val listener = AuthListener(authFlow)
        val channel = interfaceStub(Channel::class.java)

        listener.onOnlineAuth(
            OpenStartAuthEvent(
                userName = "OfflinePlayer",
                userUUID = UUID.randomUUID(),
                serverId = "server-id",
                playerIp = "127.0.0.1",
                channel = channel,
                isOnline = false
            )
        )

        assertEquals(emptyList<RecordingAuthFlow.StartCall>(), authFlow.startCalls)
        assertEquals(emptyList<RecordingAuthFlow.RegisterCall>(), authFlow.registerCalls)
    }

    @Test
    fun `waiting area join without open auth does not register`() {
        val authFlow = RecordingAuthFlow()
        val listener = AuthListener(authFlow)
        val channel = interfaceStub(Channel::class.java)
        val player = interfaceStub(
            Player::class.java,
            mapOf(
                "getUsername" to "YggPlayer",
                "isOnlineMode" to true
            )
        )
        val hyperZonePlayer = interfaceStub(
            HyperZonePlayer::class.java,
            mapOf("hasAttachedProfile" to false)
        )

        listener.onWaitingAreaJoin(channel, player, hyperZonePlayer)

        assertEquals(emptyList<RecordingAuthFlow.StartCall>(), authFlow.startCalls)
        assertEquals(emptyList<RecordingAuthFlow.RegisterCall>(), authFlow.registerCalls)
    }

    private fun <T> interfaceStub(type: Class<T>, values: Map<String, Any?> = emptyMap()): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            values[method.name] ?: defaultValue(method.returnType)
        } as T
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> 0.toChar()
        else -> null
    }

    private class RecordingAuthFlow : YggdrasilAuthFlow {
        val startCalls = mutableListOf<StartCall>()
        val registerCalls = mutableListOf<RegisterCall>()
        val clearCalls = mutableListOf<Player>()

        override fun startYggdrasilAuth(
            channel: Channel,
            username: String,
            uuid: UUID,
            serverId: String,
            playerIp: String?
        ) {
            startCalls += StartCall(channel, username, uuid, serverId, playerIp)
        }

        override fun registerWaitingAreaPlayer(player: Player, waitingAreaPlayer: HyperZonePlayer) {
            registerCalls += RegisterCall(player, waitingAreaPlayer)
        }

        override fun requestWaitingAreaAuth(
            player: Player,
            session: LoginHandleSession
        ): CompletableFuture<LoginHandleResult> {
            return CompletableFuture.completedFuture(LoginHandleResult.success())
        }

        override fun clearPlayerCacheOnDisconnect(player: Player) {
            clearCalls += player
        }

        data class StartCall(
            val channel: Channel,
            val username: String,
            val uuid: UUID,
            val serverId: String,
            val playerIp: String?
        )

        data class RegisterCall(
            val player: Player,
            val hyperZonePlayer: HyperZonePlayer
        )
    }
}
