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

package icu.h2l.login.auth.offline.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode
import com.velocitypowered.api.command.CommandSource
import icu.h2l.api.command.*
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OfflineAuthBrigadierCommandsTest {
    @Test
    fun `register keeps descriptive password placeholders`() {
        val dispatcher = dispatcher("register", OfflineAuthBrigadierCommands.register())
        val registerNode = requireNotNull(dispatcher.root.getChild("register"))
        val passwordNode = requireNotNull(registerNode.getChild("password"))

        assertEquals(setOf("password"), childNames(registerNode))
        assertEquals(setOf("confirmPassword"), childNames(passwordNode))
    }

    @Test
    fun `login keeps descriptive password and as placeholders`() {
        val dispatcher = dispatcher("login", OfflineAuthBrigadierCommands.login())
        val loginNode = requireNotNull(dispatcher.root.getChild("login"))
        val passwordNode = requireNotNull(loginNode.getChild("password"))
        val asNode = requireNotNull(loginNode.getChild("as"))
        val usernameNode = requireNotNull(asNode.getChild("username"))

        assertEquals(setOf("password", "as"), childNames(loginNode))
        assertEquals(setOf("code"), childNames(passwordNode))
        assertEquals(setOf("password"), childNames(usernameNode))
    }

    @Test
    fun `email and totp keep descriptive subcommand trees`() {
        val emailDispatcher = dispatcher("email", OfflineAuthBrigadierCommands.email())
        val totpDispatcher = dispatcher("totp", OfflineAuthBrigadierCommands.totp())
        val emailNode = requireNotNull(emailDispatcher.root.getChild("email"))
        val totpNode = requireNotNull(totpDispatcher.root.getChild("totp"))

        assertEquals(setOf("add", "change", "show", "recovery", "code", "setpassword"), childNames(emailNode))
        assertEquals(setOf("add", "enable", "confirm", "remove", "disable"), childNames(totpNode))
        assertEquals(setOf("currentPassword"), childNames(requireNotNull(emailNode.getChild("add"))))
        assertEquals(setOf("password"), childNames(requireNotNull(totpNode.getChild("add"))))
    }

    @Test
    fun `login root executes with empty arguments so usage can be shown`() {
        val execution = execute(
            registrationName = "login",
            brigadier = OfflineAuthBrigadierCommands.login(),
            input = "login"
        )

        assertEquals("login", execution.alias)
        assertArrayEquals(emptyArray(), execution.args)
    }

    @Test
    fun `register root executes with empty arguments so usage can be shown`() {
        val execution = execute(
            registrationName = "register",
            brigadier = OfflineAuthBrigadierCommands.register(),
            input = "register"
        )

        assertEquals("register", execution.alias)
        assertArrayEquals(emptyArray(), execution.args)
    }

    @Test
    fun `register explicit tree still handles normal passwords`() {
        val execution = execute(
            registrationName = "register",
            brigadier = OfflineAuthBrigadierCommands.register(),
            input = "register SecurePass123 SecurePass123"
        )

        assertEquals("register", execution.alias)
        assertArrayEquals(arrayOf("SecurePass123", "SecurePass123"), execution.args)
    }

    @Test
    fun `login explicit tree still handles normal password`() {
        val execution = execute(
            registrationName = "login",
            brigadier = OfflineAuthBrigadierCommands.login(),
            input = "login SecurePass123"
        )

        assertEquals("login", execution.alias)
        assertArrayEquals(arrayOf("SecurePass123"), execution.args)
    }

    @Test
    fun `login as keeps explicit username syntax`() {
        val execution = execute(
            registrationName = "login",
            brigadier = OfflineAuthBrigadierCommands.login(),
            input = "login as Alice SecurePass123 123456"
        )

        assertEquals("login", execution.alias)
        assertArrayEquals(arrayOf("as", "Alice", "SecurePass123", "123456"), execution.args)
    }

    @Test
    fun `change password command keeps both normal passwords`() {
        val execution = execute(
            registrationName = "changepassword",
            brigadier = OfflineAuthBrigadierCommands.changePassword(),
            input = "changepassword oldPass123 newPass456"
        )

        assertEquals("changepassword", execution.alias)
        assertArrayEquals(arrayOf("oldPass123", "newPass456"), execution.args)
    }

    @Test
    fun `unregister explicit tree still handles normal password`() {
        val execution = execute(
            registrationName = "unregister",
            brigadier = OfflineAuthBrigadierCommands.unregister(),
            input = "unregister SecurePass123"
        )

        assertEquals("unregister", execution.alias)
        assertArrayEquals(arrayOf("SecurePass123"), execution.args)
    }

    @Test
    fun `email add subcommand keeps descriptive placeholders`() {
        val dispatcher = dispatcher("email", OfflineAuthBrigadierCommands.email())
        val emailNode = requireNotNull(dispatcher.root.getChild("email"))
        val addNode = requireNotNull(emailNode.getChild("add"))
        val currentPasswordNode = requireNotNull(addNode.getChild("currentPassword"))
        val emailArgNode = requireNotNull(currentPasswordNode.getChild("email"))

        assertEquals(setOf("currentPassword"), childNames(addNode))
        assertEquals(setOf("email"), childNames(currentPasswordNode))
        assertEquals(setOf("confirmEmail"), childNames(emailArgNode))
    }

    @Test
    fun `email setpassword keeps normal recovery password`() {
        val execution = execute(
            registrationName = "email",
            brigadier = OfflineAuthBrigadierCommands.email(),
            input = "email setpassword SecurePass123 SecurePass123"
        )

        assertEquals("email", execution.alias)
        assertArrayEquals(arrayOf("setpassword", "SecurePass123", "SecurePass123"), execution.args)
    }

    @Test
    fun `totp commands keep normal password arguments`() {
        val addExecution = execute(
            registrationName = "totp",
            brigadier = OfflineAuthBrigadierCommands.totp(),
            input = "totp add SecurePass123"
        )
        val removeExecution = execute(
            registrationName = "totp",
            brigadier = OfflineAuthBrigadierCommands.totp(),
            input = "totp remove SecurePass123 123456"
        )

        assertEquals("totp", addExecution.alias)
        assertArrayEquals(arrayOf("add", "SecurePass123"), addExecution.args)
        assertEquals("totp", removeExecution.alias)
        assertArrayEquals(arrayOf("remove", "SecurePass123", "123456"), removeExecution.args)
    }

    private fun execute(
        registrationName: String,
        brigadier: HyperChatBrigadierRegistration,
        input: String
    ): CapturedExecution {
        var alias: String? = null
        var args: Array<String>? = null
        val dispatcher = dispatcher(registrationName, brigadier) { commandAlias, commandArgs ->
                alias = commandAlias
                args = commandArgs
                1
            }

        val source = mockk<CommandSource>(relaxed = true)
        dispatcher.execute(input, source)

        return CapturedExecution(
            alias = requireNotNull(alias),
            args = requireNotNull(args)
        )
    }

    private fun dispatcher(
        registrationName: String,
        brigadier: HyperChatBrigadierRegistration,
        executor: (String, Array<String>) -> Int = { _, _ -> 1 }
    ): CommandDispatcher<CommandSource> {
        val registration = HyperChatCommandRegistration(
            name = registrationName,
            executor = NoopExecutor,
            brigadier = brigadier
        )
        val context = HyperChatBrigadierContext(
            registration = registration,
            visibility = { true },
            executor = { _, commandAlias, commandArgs ->
                executor(commandAlias, commandArgs)
            }
        )

        return CommandDispatcher<CommandSource>().also { dispatcher ->
            dispatcher.register(brigadier.create(context))
        }
    }

    private fun childNames(node: CommandNode<CommandSource>): Set<String> {
        return node.children.mapTo(linkedSetOf()) { it.name }
    }

    private class CapturedExecution(
        val alias: String,
        val args: Array<String>
    )

    private object NoopExecutor : HyperChatCommandExecutor {
        override fun execute(invocation: HyperChatCommandInvocation) = Unit
    }
}


