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

package icu.h2l.login.vServer.limbo

import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.api.command.HyperChatCommandRegistration
import icu.h2l.api.event.vServer.VServerAuthStartEvent
import icu.h2l.api.player.getChannel
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.login.vServer.limbo.handler.LimboAuthSessionHandler
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.player.VelocityHyperZonePlayer
import net.elytrium.limboapi.api.Limbo
import net.elytrium.limboapi.api.LimboFactory
import net.elytrium.limboapi.api.chunk.Dimension
import net.elytrium.limboapi.api.chunk.VirtualWorld
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent
import net.elytrium.limboapi.api.player.GameMode
import net.elytrium.limboapi.api.player.LimboPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter over the real Limbo API. This class bridges the third-party Limbo API
 * to our internal adapter interface. Only construct this when Limbo is present.
 */
class LimboVServerAuth(server: ProxyServer) : HyperZoneVServerAdapter {
    private val factory: LimboFactory
    private lateinit var limboAuthServer: Limbo
    private val limboSessions = ConcurrentHashMap<io.netty.channel.Channel, LimboPlayer>()

    init {
        factory = server.pluginManager.getPlugin("limboapi")
            .flatMap { obj: PluginContainer -> obj.instance }
            .orElseThrow() as LimboFactory
    }

    fun load() {
        val authWorld: VirtualWorld = factory.createVirtualWorld(
            Dimension.OVERWORLD,
            0.0, 0.0, 0.0,
            0f, 0f
        )

        limboAuthServer = factory
            .createLimbo(authWorld)
            .setName("HyperzoneLogin")
            .setWorldTime(1000L)
            .setGameMode(GameMode.ADVENTURE)
    }

    @Subscribe
    fun onLoginLimboRegister(event: LoginLimboRegisterEvent) {
        event.addOnJoinCallback { authPlayer(event.player) }
    }

    override fun authPlayer(player: Player) {
        val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(player)

        val VServerAuthStartEvent = VServerAuthStartEvent(player, hyperZonePlayer)
        HyperZoneLoginMain.getInstance().proxy.eventManager.fire(VServerAuthStartEvent).join()
        if (VServerAuthStartEvent.pass) {
            factory.passLoginLimbo(player)
            return
        }

        val newHandler = LimboAuthSessionHandler(player, hyperZonePlayer) { proxyPlayer, zonePlayer, limboPlayer ->
            bindSession(proxyPlayer, limboPlayer)
            (zonePlayer as? VelocityHyperZonePlayer)?.update(limboPlayer.proxyPlayer)
        }
        limboAuthServer.spawnPlayer(player, newHandler)
    }

    override fun isPlayerInWaitingArea(player: Player): Boolean {
        return limboSessions.containsKey(player.getChannel())
    }

    override fun exitWaitingArea(player: Player): Boolean {
        /**
         * Limbo 的退出语义和后端等待服不同：
         * 对 Limbo 来说，断开当前 Limbo 会话本身就是“离开等待区”的原生实现。
         */
        val limboPlayer = limboSessions.remove(player.getChannel()) ?: return false
        limboPlayer.disconnect()
        return true
    }

    override fun onVerified(player: Player) {
        limboSessions.remove(player.getChannel())?.disconnect()
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        limboSessions.remove(event.player.getChannel())
    }

    override fun registerCommand(meta: CommandMeta, registration: HyperChatCommandRegistration) {
        limboAuthServer.registerCommand(meta, object : SimpleCommand {
            override fun execute(invocation: SimpleCommand.Invocation) {
                registration.executor.execute(
                    LimboInvocation(
                        invocation.source(),
                        invocation.alias(),
                        invocation.arguments()
                    )
                )
            }

            override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
                return registration.executor.hasPermission(
                    LimboInvocation(
                        invocation.source(),
                        invocation.alias(),
                        invocation.arguments()
                    )
                )
            }
        })
    }

    private class LimboInvocation(
        private val source: com.velocitypowered.api.command.CommandSource,
        private val alias: String,
        private val arguments: Array<String>
    ) : HyperChatCommandInvocation {
        override fun source() = source
        override fun arguments(): Array<String> = arguments
        override fun alias(): String = alias
    }

    private fun bindSession(player: Player, limboPlayer: LimboPlayer) {
        limboSessions[player.getChannel()] = limboPlayer
    }
}

