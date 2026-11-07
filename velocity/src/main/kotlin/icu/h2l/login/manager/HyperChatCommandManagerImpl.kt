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
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import com.mojang.brigadier.tree.RootCommandNode
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.proxy.command.VelocityCommands
import com.velocitypowered.proxy.command.brigadier.VelocityArgumentBuilder
import com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket
import icu.h2l.api.command.HyperChatBrigadierContext
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.api.command.HyperChatCommandManager
import icu.h2l.api.command.HyperChatCommandRegistration
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.message.MessageKeys
import java.lang.invoke.MethodHandles
import java.util.concurrent.ConcurrentHashMap

object HyperChatCommandManagerImpl : HyperChatCommandManager {
    private val availableCommandsRootNodeSetter by lazy {
        MethodHandles.privateLookupIn(AvailableCommandsPacket::class.java, MethodHandles.lookup())
            .findSetter(AvailableCommandsPacket::class.java, "rootNode", RootCommandNode::class.java)
    }

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
        val proxy = proxyServer
        if (proxy != null && proxyRegisteredCommands.remove(registration.name.lowercase())) {
            proxy.commandManager.unregister(registration.name)
        }
    }

    override fun executeChat(source: CommandSource, chat: String): Boolean {
        val messages = HyperZoneLoginMain.getInstance().messageService
        val input = chat.trim()
        val hyperPlayer = (source as? Player)?.let { player ->
            runCatching {
                HyperZonePlayerManager.getByPlayer(player)
            }.getOrNull()
        }
        if (!input.startsWith("/")) {
            if (hyperPlayer != null && !hyperPlayer.hasAttachedUser()) {
                messages.send(source, MessageKeys.Chat.MUST_VERIFY_BEFORE_CHAT)
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
            if (hyperPlayer != null && !hyperPlayer.hasAttachedUser()) {
                messages.send(source, MessageKeys.Chat.ONLY_ALLOWED_COMMANDS)
                return true
            }
            return false
        }
        val invocation = ChatInvocation(source, label, args)
        if (!registration.executor.hasPermission(invocation)) {
            messages.send(source, MessageKeys.Common.NO_PERMISSION)
            return true
        }

        if (source is Player) {
            val adapter = vServerAdapter
            val proxy = proxyServer
            if (adapter != null && proxy != null && adapter.allowsProxyFallbackCommand(source)) {
                proxy.commandManager.executeImmediatelyAsync(source, body)
                return true
            }
        }

        registration.executor.execute(invocation)
        return true
    }

    fun createAvailableCommandsPacket(source: Player): AvailableCommandsPacket {
        return populateAvailableCommandsPacket(AvailableCommandsPacket(), source)
    }

    fun createEmptyAvailableCommandsPacket(): AvailableCommandsPacket {
        val packet = AvailableCommandsPacket()
        availableCommandsRootNodeSetter.invoke(packet, RootCommandNode<CommandSource>())
        return packet
    }

    fun populateAvailableCommandsPacket(packet: AvailableCommandsPacket, source: Player): AvailableCommandsPacket {
        val root = buildProxyFallbackCommandRoot(source)
        availableCommandsRootNodeSetter.invoke(packet, root)
        return packet
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
            visibility = { source -> allowsProxyFallbackCommand(registration, source) },
            executor = { source, alias, args -> executeProxyFallback(registration, source, alias, args) }
        )
        val rootBuilder = createProxyFallbackCommandTree(registration, brigadierContext)
        val brigadierCommand = BrigadierCommand(rootBuilder)

        val metaBuilder = proxy.commandManager.metaBuilder(brigadierCommand)
        if (registration.aliases.isNotEmpty()) {
            metaBuilder.aliases(*registration.aliases.toTypedArray())
        }

        proxy.commandManager.register(metaBuilder.build(), brigadierCommand)
    }

    private fun allowsProxyFallbackCommand(
        registration: HyperChatCommandRegistration,
        source: CommandSource
    ): Boolean {
        if (source !is Player) {
            return false
        }

        val adapter = vServerAdapter ?: return false
        if (!adapter.allowsProxyFallbackCommand(source)) {
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
    }

    internal fun createProxyFallbackCommandTree(
        registration: HyperChatCommandRegistration,
        context: HyperChatBrigadierContext
    ): LiteralArgumentBuilder<CommandSource> {
        val rootBuilder = registration.brigadier?.create(context)
            ?: createDefaultProxyFallbackCommand(context)

        val rootNode = rootBuilder.build()
        if (rootNode.getChild(VelocityCommands.ARGS_NODE_NAME) == null) {
            rootBuilder.then(
                VelocityArgumentBuilder.velocityArgument<CommandSource, String>(
                    VelocityCommands.ARGS_NODE_NAME,
                    StringArgumentType.greedyString()
                )
                    .executes { commandContext ->
                        context.executeGreedy(
                            commandContext.source,
                            StringArgumentType.getString(commandContext, VelocityCommands.ARGS_NODE_NAME)
                        )
                    }
                    .build()
            )
        }

        return rootBuilder
    }

    internal fun buildProxyFallbackCommandRoot(source: CommandSource): RootCommandNode<CommandSource> {
        val root = RootCommandNode<CommandSource>()
        getRegisteredCommands().forEach { registration ->
            if (!allowsProxyFallbackCommand(registration, source)) {
                return@forEach
            }

            val context = HyperChatBrigadierContext(
                registration = registration,
                visibility = { commandSource -> allowsProxyFallbackCommand(registration, commandSource) },
                executor = { commandSource, alias, args -> executeProxyFallback(registration, commandSource, alias, args) },
            )
            val primaryNode = createProxyFallbackCommandTree(registration, context).build()
            addRootLiteral(root, primaryNode)
            registration.aliases.forEach { alias ->
                addRootLiteral(root, VelocityCommands.shallowCopy(primaryNode, alias))
            }
        }
        return root
    }

    private fun addRootLiteral(
        root: RootCommandNode<CommandSource>,
        node: LiteralCommandNode<CommandSource>,
    ) {
        root.removeChildByName(node.name)
        root.addChild(node)
    }

    private fun executeProxyFallback(
        registration: HyperChatCommandRegistration,
        source: CommandSource,
        alias: String,
        args: Array<String>
    ): Int {
        val messages = HyperZoneLoginMain.getInstance().messageService
        if (source !is Player) {
            messages.send(source, MessageKeys.Common.ONLY_PLAYER)
            return Command.SINGLE_SUCCESS
        }

        val adapter = vServerAdapter
        if (adapter == null || !adapter.allowsProxyFallbackCommand(source)) {
            messages.send(source, MessageKeys.Chat.WAITING_AREA_ONLY)
            return Command.SINGLE_SUCCESS
        }

        val invocation = ChatInvocation(source, alias, args)
        if (!registration.executor.hasPermission(invocation)) {
            messages.send(source, MessageKeys.Common.NO_PERMISSION)
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
