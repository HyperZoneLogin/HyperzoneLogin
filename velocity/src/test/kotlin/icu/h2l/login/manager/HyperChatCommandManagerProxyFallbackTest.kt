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

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.CommandNode
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.proxy.command.VelocityCommands
import icu.h2l.api.command.*
import icu.h2l.login.command.BindCodeBrigadierCommands
import icu.h2l.login.command.RenameCommand
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HyperChatCommandManagerProxyFallbackTest {
    @Test
    fun `proxy fallback tree adds velocity arguments node while keeping hint branches`() {
        val context = newContext()
        val builder = HyperChatCommandManagerImpl.createProxyFallbackCommandTree(
            registration = registration(HintingBrigadier),
            context = context
        )
        val root = builder.build()

        assertEquals(setOf("password", "as", VelocityCommands.ARGS_NODE_NAME), childNames(root))
    }

    @Test
    fun `velocity arguments node accepts special character password`() {
        val context = newContext()
        var capturedArgs: Array<String>? = null
        val registration = registration(HintingBrigadier)
        val bridgContext = newContext(registration) { _, args ->
            capturedArgs = args
            1
        }
        val builder = HyperChatCommandManagerImpl.createProxyFallbackCommandTree(registration, bridgContext)
        val dispatcher = CommandDispatcher<CommandSource>()
        val source = mockk<CommandSource>(relaxed = true)

        dispatcher.register(builder)
        dispatcher.execute("login !@#$1234", source)

        assertArrayEquals(arrayOf("!@#$1234"), capturedArgs)
    }

    @Test
    fun `explicit hint branch still works for normal password input`() {
        val registration = registration(HintingBrigadier)
        var capturedArgs: Array<String>? = null
        val context = newContext(registration) { _, args ->
            capturedArgs = args
            1
        }
        val dispatcher = CommandDispatcher<CommandSource>()
        val source = mockk<CommandSource>(relaxed = true)

        dispatcher.register(HyperChatCommandManagerImpl.createProxyFallbackCommandTree(registration, context))
        dispatcher.execute("login pass123", source)

        assertArrayEquals(arrayOf("pass123"), capturedArgs)
    }

    @Test
    fun `root command remains executable for usage output`() {
        val registration = registration(HintingBrigadier)
        var capturedArgs: Array<String>? = null
        val context = newContext(registration) { _, args ->
            capturedArgs = args
            1
        }
        val dispatcher = CommandDispatcher<CommandSource>()
        val source = mockk<CommandSource>(relaxed = true)

        dispatcher.register(HyperChatCommandManagerImpl.createProxyFallbackCommandTree(registration, context))
        dispatcher.execute("login", source)

        assertArrayEquals(emptyArray(), capturedArgs)
    }

    @Test
    fun `rename command keeps hint node and raw arguments bridge`() {
        val registration = HyperChatCommandRegistration(
            name = "rename",
            executor = NoopExecutor,
            brigadier = RenameCommand.brigadier()
        )
        val builder = HyperChatCommandManagerImpl.createProxyFallbackCommandTree(registration, newContext(registration))
        val root = builder.build()

        assertEquals(setOf("name", VelocityCommands.ARGS_NODE_NAME), childNames(root))
    }

    @Test
    fun `bindcode command keeps explicit subcommands and accepts special characters through raw bridge`() {
        val registration = HyperChatCommandRegistration(
            name = "bindcode",
            aliases = setOf("bcode"),
            executor = NoopExecutor,
            brigadier = BindCodeBrigadierCommands.bindCode()
        )
        var capturedArgs: Array<String>? = null
        val context = newContext(registration) { _, args ->
            capturedArgs = args
            1
        }
        val dispatcher = CommandDispatcher<CommandSource>()
        val source = mockk<CommandSource>(relaxed = true)

        dispatcher.register(HyperChatCommandManagerImpl.createProxyFallbackCommandTree(registration, context))
        dispatcher.execute("bindcode use !@#CODE", source)

        val root = requireNotNull(dispatcher.root.getChild("bindcode"))
        assertEquals(setOf("generate", "use", VelocityCommands.ARGS_NODE_NAME), childNames(root))
        assertArrayEquals(arrayOf("use", "!@#CODE"), capturedArgs)
    }

    @Test
    fun `default root-only command still gets raw arguments bridge`() {
        val registration = HyperChatCommandRegistration(
            name = "exit",
            executor = NoopExecutor,
            brigadier = null
        )
        var capturedArgs: Array<String>? = null
        val context = newContext(registration) { _, args ->
            capturedArgs = args
            1
        }
        val dispatcher = CommandDispatcher<CommandSource>()
        val source = mockk<CommandSource>(relaxed = true)

        dispatcher.register(HyperChatCommandManagerImpl.createProxyFallbackCommandTree(registration, context))
        dispatcher.execute("exit anything-goes", source)

        assertEquals(setOf(VelocityCommands.ARGS_NODE_NAME), childNames(dispatcher.root.getChild("exit")!!))
        assertArrayEquals(arrayOf("anything-goes"), capturedArgs)
    }

    private fun registration(
        brigadier: HyperChatBrigadierRegistration
    ) = HyperChatCommandRegistration(
        name = "login",
        executor = NoopExecutor,
        brigadier = brigadier
    )

    private fun newContext(
        registration: HyperChatCommandRegistration = registration(HintingBrigadier),
        executor: (String, Array<String>) -> Int = { _, _ -> 1 }
    ) = HyperChatBrigadierContext(
        registration = registration,
        visibility = { true },
        executor = { _, alias, args -> executor(alias, args) }
    )

    private fun childNames(node: CommandNode<CommandSource>): Set<String> {
        return node.children.mapTo(linkedSetOf()) { it.name }
    }

    private object HintingBrigadier : HyperChatBrigadierRegistration {
        override fun create(context: HyperChatBrigadierContext) =
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(
                    BrigadierCommand.requiredArgumentBuilder("password", StringArgumentType.word())
                        .executes { commandContext ->
                            context.execute(
                                commandContext.source,
                                args = arrayOf(StringArgumentType.getString(commandContext, "password"))
                            )
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("as")
                        .then(
                            BrigadierCommand.requiredArgumentBuilder("username", StringArgumentType.word())
                        )
                )
    }

    private object NoopExecutor : HyperChatCommandExecutor {
        override fun execute(invocation: HyperChatCommandInvocation) = Unit
    }
}

