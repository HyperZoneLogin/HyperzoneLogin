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
import icu.h2l.api.config.HZLConfigRegistry
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.profile.HyperZoneUserServiceProvider
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.player.VelocityHyperZonePlayer
import icu.h2l.login.profile.UserBindingCodeService
import java.util.*

class HyperZoneLoginCommand(
    private val bindingCodeService: UserBindingCodeService
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
                    BrigadierCommand.literalArgumentBuilder("config")
                        .requires { source -> source.hasPermission(ADMIN_PERMISSION) }
                        .then(
                            BrigadierCommand.literalArgumentBuilder("regenerate")
                                .executes { context ->
                                    executeConfigRegenerate(context.source)
                                }
                        )
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
                .then(
                    BrigadierCommand.literalArgumentBuilder("user")
                        .executes { context ->
                            executeUserList(context.source)
                        }
                        .then(
                            BrigadierCommand.literalArgumentBuilder("list")
                                .executes { context ->
                                    executeUserList(context.source)
                                }
                        )
                        .then(
                            BrigadierCommand.literalArgumentBuilder("use")
                                .then(
                                    BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.greedyString())
                                        .executes { context ->
                                            executeUserUse(
                                                context.source,
                                                StringArgumentType.getString(context, "target")
                                            )
                                        }
                                )
                        )
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("sync")
                        .requires { source -> source.hasPermission(ADMIN_PERMISSION) }
                        .executes { context ->
                            executeSync(context.source, null)
                        }
                        .then(
                            BrigadierCommand.requiredArgumentBuilder("userUUID", StringArgumentType.word())
                                .executes { context ->
                                    executeSync(
                                        context.source,
                                        StringArgumentType.getString(context, "userUUID")
                                    )
                                }
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
        messages.send(sender, MessageKeys.HzlCommand.User.USAGE)
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            messages.send(sender, MessageKeys.HzlCommand.USAGE_UUID)
            messages.send(sender, MessageKeys.HzlCommand.Sync.USAGE)
        }
    }

    private fun executeReload(sender: CommandSource): Int {
        val main = HyperZoneLoginMain.getInstance()
        main.reloadRuntimeConfigs()
        main.messageService.send(sender, MessageKeys.HzlCommand.RELOADED)
        return Command.SINGLE_SUCCESS
    }

    private fun executeConfigRegenerate(sender: CommandSource): Int {
        val messages = HyperZoneLoginMain.getInstance().messageService
        val count = HZLConfigRegistry.regenerateAll()
        messages.send(sender, MessageKeys.HzlCommand.CONFIG_REGENERATED, HyperZoneMessagePlaceholder.text("count", count))
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
        val userService = HyperZoneUserServiceProvider.get()
        val user = userService.getAttachedUser(hyperZonePlayer)

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
                MessageKeys.HzlCommand.UUID_SESSION,
                HyperZoneMessagePlaceholder.text("name", hyperZonePlayer.logLabel),
                HyperZoneMessagePlaceholder.text("uuid", hyperZonePlayer.userUUID)
            )
        )
        sender.sendMessage(
            messages.render(
                sender,
                MessageKeys.HzlCommand.UUID_HYPER_PLAYER,
                    HyperZoneMessagePlaceholder.text("attached_user", hyperZonePlayer.hasAttachedUser()),
                    HyperZoneMessagePlaceholder.text("registration_name",
                            hyperZonePlayer.getSubmittedCredentials().firstOrNull()?.getRegistrationName()
                                ?: hyperZonePlayer.logLabel),
                HyperZoneMessagePlaceholder.text(
                        "can_create_user",
                        (userService as? icu.h2l.login.profile.VelocityHyperZoneUserService)
                            ?.canCreateUser(hyperZonePlayer.getSubmittedCredentials().firstOrNull()?.getRegistrationName()
                                ?: hyperZonePlayer.logLabel) ?: false
                ),
                HyperZoneMessagePlaceholder.text("credentials", hyperZonePlayer.getSubmittedCredentials().size)
            )
        )
        if (user != null) {
            val activeIdentity = hyperZonePlayer.activeInGameProfile
            sender.sendMessage(
                messages.render(
                    sender,
                    MessageKeys.HzlCommand.UUID_USER,
                    HyperZoneMessagePlaceholder.text("id", user.id),
                    HyperZoneMessagePlaceholder.text("name", activeIdentity?.name ?: "-"),
                    HyperZoneMessagePlaceholder.text("uuid", activeIdentity?.uuid ?: "-")
                )
            )
        } else {
            messages.send(sender, MessageKeys.HzlCommand.UUID_USER_NULL)
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

    private fun executeUserList(sender: CommandSource): Int {
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

        val main = HyperZoneLoginMain.getInstance()
        val user = main.userService.getAttachedUser(hyperZonePlayer)
        if (user == null) {
            messages.send(sender, MessageKeys.HzlCommand.User.NO_USER)
            return Command.SINGLE_SUCCESS
        }

        val records = main.userService.getInGameProfiles(user.id)
        if (records.isEmpty()) {
            messages.send(sender, MessageKeys.HzlCommand.User.EMPTY)
            return Command.SINGLE_SUCCESS
        }

        val activeId = hyperZonePlayer.activeInGameProfile?.id
            ?: user.activeInGameProfileId
        messages.send(
            sender,
            MessageKeys.HzlCommand.User.LIST_HEADER,
            HyperZoneMessagePlaceholder.text("user_uuid", user.id),
            HyperZoneMessagePlaceholder.text("count", records.size),
        )
        records.forEachIndexed { index, record ->
            val key = if (record.id == activeId) {
                MessageKeys.HzlCommand.User.ENTRY_ACTIVE
            } else {
                MessageKeys.HzlCommand.User.ENTRY
            }
            messages.send(
                sender,
                key,
                HyperZoneMessagePlaceholder.text("index", index + 1),
                HyperZoneMessagePlaceholder.text("name", record.name),
                HyperZoneMessagePlaceholder.text("uuid", record.uuid),
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeUserUse(sender: CommandSource, rawTarget: String): Int {
        val messages = HyperZoneLoginMain.getInstance().messageService
        if (sender !is Player) {
            messages.send(sender, MessageKeys.Common.ONLY_PLAYER)
            return Command.SINGLE_SUCCESS
        }

        val target = rawTarget.trim()
        if (target.isEmpty()) {
            messages.send(sender, MessageKeys.HzlCommand.User.USAGE)
            return Command.SINGLE_SUCCESS
        }

        val hyperZonePlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(sender)
        }.getOrElse {
            messages.send(sender, MessageKeys.Common.PLAYER_STATE_UNAVAILABLE)
            return Command.SINGLE_SUCCESS
        }

        val main = HyperZoneLoginMain.getInstance()
        val user = main.userService.getAttachedUser(hyperZonePlayer)
        if (user == null) {
            messages.send(sender, MessageKeys.HzlCommand.User.NO_USER)
            return Command.SINGLE_SUCCESS
        }

        val records = main.userService.getInGameProfiles(user.id)
        val selected = target.toIntOrNull()
            ?.takeIf { it in 1..records.size }
            ?.let { records[it - 1] }
            ?: runCatching { UUID.fromString(target) }.getOrNull()
                ?.let { uuid -> records.firstOrNull { it.id == uuid } }
            ?: records.firstOrNull { it.uuid.toString().equals(target, ignoreCase = true) }
            ?: records.firstOrNull { it.name.equals(target, ignoreCase = true) }

        if (selected == null) {
            messages.send(
                sender,
                MessageKeys.HzlCommand.User.NOT_FOUND,
                HyperZoneMessagePlaceholder.text("target", target),
            )
            return Command.SINGLE_SUCCESS
        }

        runCatching {
            val switched = main.userService.switchActiveInGameProfile(user.id, selected.id)
            (hyperZonePlayer as? VelocityHyperZonePlayer)?.bindActiveInGameProfile(switched)
        }.onSuccess {
            messages.send(
                sender,
                MessageKeys.HzlCommand.User.SWITCHED,
                HyperZoneMessagePlaceholder.text("name", selected.name),
                HyperZoneMessagePlaceholder.text("uuid", selected.uuid),
            )
        }.onFailure { throwable ->
            messages.send(
                sender,
                MessageKeys.HzlCommand.User.NOT_FOUND,
                HyperZoneMessagePlaceholder.text("target", target),
                HyperZoneMessagePlaceholder.text("reason", throwable.message ?: "unknown"),
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeSync(sender: CommandSource, rawUserUUID: String?): Int {
        val messages = HyperZoneLoginMain.getInstance().messageService
        val main = HyperZoneLoginMain.getInstance()

        val userUUID = if (rawUserUUID.isNullOrBlank()) {
            if (sender !is Player) {
                messages.send(sender, MessageKeys.HzlCommand.Sync.CONSOLE_NEEDS_ID)
                return Command.SINGLE_SUCCESS
            }
            val hyperZonePlayer = runCatching {
                HyperZonePlayerManager.getByPlayer(sender)
            }.getOrElse {
                messages.send(sender, MessageKeys.Common.PLAYER_STATE_UNAVAILABLE)
                return Command.SINGLE_SUCCESS
            }
            val user = main.userService.getAttachedUser(hyperZonePlayer)
            if (user == null) {
                messages.send(sender, MessageKeys.HzlCommand.Sync.NO_USER)
                return Command.SINGLE_SUCCESS
            }
            user.id
        } else {
            runCatching { UUID.fromString(rawUserUUID.trim()) }.getOrElse {
                messages.send(
                    sender,
                    MessageKeys.HzlCommand.Sync.INVALID_ID,
                    HyperZoneMessagePlaceholder.text("user_uuid", rawUserUUID),
                )
                return Command.SINGLE_SUCCESS
            }
        }

        if (main.userService.getUser(userUUID) == null) {
            messages.send(
                sender,
                MessageKeys.HzlCommand.Sync.NOT_FOUND,
                HyperZoneMessagePlaceholder.text("user_uuid", userUUID),
            )
            return Command.SINGLE_SUCCESS
        }

        val result = try {
            main.inGameProfileSyncService.addMissingInGameProfiles(userUUID)
        } catch (throwable: Throwable) {
            messages.send(
                sender,
                MessageKeys.HzlCommand.Sync.FAILED,
                HyperZoneMessagePlaceholder.text("reason", throwable.message ?: "unknown"),
            )
            return Command.SINGLE_SUCCESS
        }

        messages.send(
            sender,
            MessageKeys.HzlCommand.Sync.RESULT,
            HyperZoneMessagePlaceholder.text("user_uuid", userUUID),
            HyperZoneMessagePlaceholder.text("scanned", result.scanned),
            HyperZoneMessagePlaceholder.text("added", result.added),
            HyperZoneMessagePlaceholder.text("existing", result.existing),
            HyperZoneMessagePlaceholder.text("conflicts", result.conflicts.size),
        )
        result.conflicts.forEach { conflict ->
            messages.send(
                sender,
                MessageKeys.HzlCommand.Sync.CONFLICT,
                HyperZoneMessagePlaceholder.text("channel", conflict.candidate.channel),
                HyperZoneMessagePlaceholder.text("name", conflict.candidate.name),
                HyperZoneMessagePlaceholder.text("uuid", conflict.candidate.uuid ?: "-"),
                HyperZoneMessagePlaceholder.text("reason", conflict.reason),
            )
        }
        return Command.SINGLE_SUCCESS
    }

    companion object {
        private const val ADMIN_PERMISSION = "hyperzonelogin.admin"
    }
} 