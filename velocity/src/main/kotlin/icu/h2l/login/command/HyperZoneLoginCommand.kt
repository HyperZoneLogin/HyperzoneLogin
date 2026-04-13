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

package icu.h2l.login.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.profile.HyperZoneProfileServiceProvider
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.profile.ProfileBindingCodeService

class HyperZoneLoginCommand(
    private val bindingCodeService: ProfileBindingCodeService
) {
    fun createCommand(): BrigadierCommand {
        return BrigadierCommand(
            BrigadierCommand.literalArgumentBuilder("hzl")
                .executes { context ->
                    showUsage(context.source)
                    Command.SINGLE_SUCCESS
                }
                .then(
                    BrigadierCommand.literalArgumentBuilder("reload")
                        .requires { source -> source.hasPermission(ADMIN_PERMISSION) }
                        .executes { context ->
                            executeReload(context.source)
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("re")
                        .executes { context ->
                            executeReAuth(context.source)
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("uuid")
                        .requires { source -> source.hasPermission(ADMIN_PERMISSION) }
                        .executes { context ->
                            executeUuid(context.source)
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("bindcode")
                        .executes { context ->
                            executeBindCodeGenerate(context.source)
                        }
                        .then(
                            BrigadierCommand.literalArgumentBuilder("generate")
                                .executes { context ->
                                    executeBindCodeGenerate(context.source)
                                }
                        )
                        .then(
                            BrigadierCommand.literalArgumentBuilder("use")
                                .then(
                                    BrigadierCommand.requiredArgumentBuilder("code", StringArgumentType.word())
                                        .executes { context ->
                                            executeBindCodeUse(
                                                context.source,
                                                StringArgumentType.getString(context, "code")
                                            )
                                        }
                                )
                        )
                )
        )
    }

    private fun showUsage(sender: CommandSource) {
        val messages = HyperZoneLoginMain.getInstance().messageService
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            messages.send(sender, MessageKeys.HzlCommand.USAGE_RELOAD)
        }
        messages.send(sender, MessageKeys.HzlCommand.USAGE_RE)
        messages.send(sender, MessageKeys.HzlCommand.USAGE_BINDCODE_GENERATE)
        messages.send(sender, MessageKeys.HzlCommand.USAGE_BINDCODE_USE)
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            messages.send(sender, MessageKeys.HzlCommand.USAGE_UUID)
        }
    }

    private fun executeReload(sender: CommandSource): Int {
        val main = HyperZoneLoginMain.getInstance()
        main.reloadRuntimeConfigs()
        main.messageService.send(sender, MessageKeys.HzlCommand.RELOADED)
        return Command.SINGLE_SUCCESS
    }

    private fun executeReAuth(sender: CommandSource): Int {
        val messages = HyperZoneLoginMain.getInstance().messageService
        if (sender !is Player) {
            messages.send(sender, MessageKeys.Common.ONLY_PLAYER)
            return Command.SINGLE_SUCCESS
        }

        messages.send(sender, MessageKeys.HzlCommand.REAUTH_START)
        HyperZoneLoginMain.getInstance().triggerVServerReJoinForPlayer(sender)
        return Command.SINGLE_SUCCESS
    }

    private fun executeUuid(sender: CommandSource): Int {
        val messages = HyperZoneLoginMain.getInstance().messageService
        if (sender !is Player) {
            messages.send(sender, MessageKeys.Common.ONLY_PLAYER)
            return Command.SINGLE_SUCCESS
        }

        val proxyPlayer = sender
        val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(proxyPlayer)
        val profileService = HyperZoneProfileServiceProvider.get()
        val profile = profileService.getAttachedProfile(hyperZonePlayer)

        sender.sendMessage(
            messages.render(
                sender,
                MessageKeys.HzlCommand.UUID_PROXY_PLAYER,
                HyperZoneMessagePlaceholder.text("name", proxyPlayer.username),
                HyperZoneMessagePlaceholder.text("uuid", proxyPlayer.uniqueId)
            )
        )
        sender.sendMessage(
            messages.render(
                sender,
                MessageKeys.HzlCommand.UUID_CLIENT_ORIGINAL,
                HyperZoneMessagePlaceholder.text("name", hyperZonePlayer.clientOriginalName),
                HyperZoneMessagePlaceholder.text("uuid", hyperZonePlayer.clientOriginalUUID)
            )
        )
        sender.sendMessage(
            messages.render(
                sender,
                MessageKeys.HzlCommand.UUID_HYPER_PLAYER,
                HyperZoneMessagePlaceholder.text("verified", hyperZonePlayer.isVerified()),
                HyperZoneMessagePlaceholder.text("attached_profile", hyperZonePlayer.hasAttachedProfile()),
                HyperZoneMessagePlaceholder.text("waiting_area", hyperZonePlayer.isInWaitingArea()),
                HyperZoneMessagePlaceholder.text(
                    "can_resolve_or_create_profile",
                    profileService.canResolveOrCreateProfile(hyperZonePlayer.clientOriginalName)
                ),
                HyperZoneMessagePlaceholder.text("credentials", hyperZonePlayer.getSubmittedCredentials().size)
            )
        )
        if (profile != null) {
            sender.sendMessage(
                messages.render(
                    sender,
                    MessageKeys.HzlCommand.UUID_PROFILE,
                    HyperZoneMessagePlaceholder.text("id", profile.id),
                    HyperZoneMessagePlaceholder.text("name", profile.name),
                    HyperZoneMessagePlaceholder.text("uuid", profile.uuid)
                )
            )
        } else {
            messages.send(sender, MessageKeys.HzlCommand.UUID_PROFILE_NULL)
        }

        return Command.SINGLE_SUCCESS
    }

    private fun executeBindCodeGenerate(sender: CommandSource): Int {
        val messages = HyperZoneLoginMain.getInstance().messageService
        if (sender !is Player) {
            messages.send(sender, MessageKeys.Common.ONLY_PLAYER)
            return Command.SINGLE_SUCCESS
        }

        val hyperZonePlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(sender)
        }.getOrElse {
            messages.send(sender, MessageKeys.Common.PLAYER_STATE_UNAVAILABLE)
            return Command.SINGLE_SUCCESS
        }

        sender.sendMessage(bindingCodeService.generate(hyperZonePlayer).message)
        return Command.SINGLE_SUCCESS
    }

    private fun executeBindCodeUse(sender: CommandSource, code: String): Int {
        val messages = HyperZoneLoginMain.getInstance().messageService
        if (sender !is Player) {
            messages.send(sender, MessageKeys.Common.ONLY_PLAYER)
            return Command.SINGLE_SUCCESS
        }

        val hyperZonePlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(sender)
        }.getOrElse {
            messages.send(sender, MessageKeys.Common.PLAYER_STATE_UNAVAILABLE)
            return Command.SINGLE_SUCCESS
        }

        sender.sendMessage(bindingCodeService.use(hyperZonePlayer, code).message)
        return Command.SINGLE_SUCCESS
    }

    companion object {
        private const val ADMIN_PERMISSION = "hyperzonelogin.admin"
    }
} 