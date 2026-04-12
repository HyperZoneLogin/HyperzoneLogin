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

package icu.h2l.login.manager

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.command.HyperChatBrigadierContext
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.api.command.HyperChatCommandManager
import icu.h2l.api.command.HyperChatCommandRegistration
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import net.kyori.adventure.text.Component
import java.util.concurrent.ConcurrentHashMap

object HyperChatCommandManagerImpl : HyperChatCommandManager {
    private val commands = ConcurrentHashMap<String, HyperChatCommandRegistration>()
    @Volatile
    private var vServerAdapter: HyperZoneVServerAdapter? = null
    @Volatile
    private var proxyServer: ProxyServer? = null
    private val proxyRegisteredCommands = ConcurrentHashMap.newKeySet<String>()

    fun bindVServer(proxy: ProxyServer, adapter: HyperZoneVServerAdapter?) {
        proxyServer = proxy
        vServerAdapter = adapter
        getRegisteredCommands().forEach { registerToVServer(it) }
        getRegisteredCommands().forEach { registerToProxyFallback(it) }
    }

    override fun register(registration: HyperChatCommandRegistration) {
        commands[registration.name.lowercase()] = registration
        registration.aliases.forEach { alias ->
            commands[alias.lowercase()] = registration
        }
        registerToVServer(registration)
        registerToProxyFallback(registration)
    }

    override fun unregister(name: String) {
        val registration = commands[name.lowercase()] ?: return
        commands.entries.removeIf { (_, value) -> value === registration }
    }

    override fun executeChat(source: CommandSource, chat: String): Boolean {
        val input = chat.trim()
        val hyperPlayer = (source as? Player)?.let { player ->
            runCatching {
                HyperZonePlayerManager.getByPlayer(player)
            }.getOrNull()
        }
        if (!input.startsWith("/")) {
            if (hyperPlayer != null && hyperPlayer.isInWaitingArea()) {
                source.sendMessage(Component.text("§c您需要先通过验证才能聊天！"))
                return true
            }
            return false
        }

        val body = input.substring(1).trim()
        if (body.isEmpty()) return false

        val parts = body.split(Regex("\\s+"))
        val label = parts.first().lowercase()
        val args = if (parts.size > 1) parts.drop(1).toTypedArray() else emptyArray()

        val registration = commands[label] ?: run {
            if (hyperPlayer != null && hyperPlayer.isInWaitingArea()) {
                source.sendMessage(Component.text("§e认证阶段仅可使用 /login、/register、/changepassword、/email、/totp、/bindcode 等命令"))
                return true
            }
            return false
        }
        val invocation = ChatInvocation(source, label, args)
        if (!registration.executor.hasPermission(invocation)) {
            source.sendMessage(Component.text("§c没有权限"))
            return true
        }

        registration.executor.execute(invocation)
        return true
    }

    override fun getRegisteredCommands(): Collection<HyperChatCommandRegistration> {
        return commands.values.toSet()
    }

    private fun registerToVServer(registration: HyperChatCommandRegistration) {
        val proxy = proxyServer ?: return
        val authServer = vServerAdapter ?: return

        val metaBuilder = proxy.commandManager.metaBuilder(registration.name)
        if (registration.aliases.isNotEmpty()) {
            metaBuilder.aliases(*registration.aliases.toTypedArray())
        }

        authServer.registerCommand(metaBuilder.build(), registration)
    }

    private fun registerToProxyFallback(registration: HyperChatCommandRegistration) {
        val adapter = vServerAdapter ?: return
        if (!adapter.supportsProxyFallbackCommands()) return

        val proxy = proxyServer ?: return
        val canonicalName = registration.name.lowercase()
        if (!proxyRegisteredCommands.add(canonicalName)) {
            return
        }

        val brigadierContext = HyperChatBrigadierContext(
            registration = registration,
            visibility = { source -> canUseProxyFallbackCommand(registration, source) },
            executor = { source, alias, args -> executeProxyFallback(registration, source, alias, args) }
        )
        val rootBuilder = registration.brigadier?.create(brigadierContext)
            ?: createDefaultProxyFallbackCommand(brigadierContext)
        val brigadierCommand = BrigadierCommand(rootBuilder)

        val metaBuilder = proxy.commandManager.metaBuilder(brigadierCommand)
        if (registration.aliases.isNotEmpty()) {
            metaBuilder.aliases(*registration.aliases.toTypedArray())
        }

        proxy.commandManager.register(metaBuilder.build(), brigadierCommand)
    }

    private fun canUseProxyFallbackCommand(
        registration: HyperChatCommandRegistration,
        source: CommandSource
    ): Boolean {
        if (source !is Player) {
            return false
        }

        val adapter = vServerAdapter ?: return false
        if (!adapter.canUseProxyFallbackCommand(source)) {
            return false
        }

        return registration.executor.hasPermission(ChatInvocation(source, registration.name, emptyArray()))
    }

    private fun createDefaultProxyFallbackCommand(
        context: HyperChatBrigadierContext
    ): LiteralArgumentBuilder<CommandSource> {
        return context.literal()
            .executes { commandContext ->
                context.execute(commandContext.source)
            }
            .then(context.greedyArguments())
    }

    private fun executeProxyFallback(
        registration: HyperChatCommandRegistration,
        source: CommandSource,
        alias: String,
        args: Array<String>
    ): Int {
        if (source !is Player) {
            source.sendMessage(Component.text("§c该命令只能由玩家执行"))
            return Command.SINGLE_SUCCESS
        }

        val adapter = vServerAdapter
        if (adapter == null || !adapter.canUseProxyFallbackCommand(source)) {
            source.sendMessage(Component.text("§e该命令仅可在等待区服务器使用"))
            return Command.SINGLE_SUCCESS
        }

        val invocation = ChatInvocation(source, alias, args)
        if (!registration.executor.hasPermission(invocation)) {
            source.sendMessage(Component.text("§c没有权限"))
            return Command.SINGLE_SUCCESS
        }

        registration.executor.execute(invocation)
        return Command.SINGLE_SUCCESS
    }


    private class ChatInvocation(
        private val source: CommandSource,
        private val alias: String,
        private val args: Array<String>
    ) : HyperChatCommandInvocation {
        override fun source(): CommandSource = source
        override fun arguments(): Array<String> = args
        override fun alias(): String = alias
    }
}
