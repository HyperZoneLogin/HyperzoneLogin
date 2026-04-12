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
import icu.h2l.api.profile.HyperZoneProfileServiceProvider
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
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
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendPlainMessage("§e/hzl reload")
        }
        sender.sendPlainMessage("§e/hzl re")
        sender.sendPlainMessage("§e/hzl bindcode generate")
        sender.sendPlainMessage("§e/hzl bindcode use <绑定码>")
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendPlainMessage("§e/hzl uuid")
        }
    }

    private fun executeReload(sender: CommandSource): Int {
        sender.sendPlainMessage("§aReloaded!")
        return Command.SINGLE_SUCCESS
    }

    private fun executeReAuth(sender: CommandSource): Int {
        if (sender !is Player) {
            sender.sendPlainMessage("§c该命令只能由玩家执行")
            return Command.SINGLE_SUCCESS
        }

        sender.sendPlainMessage("§e开始重新认证...")
        HyperZoneLoginMain.getInstance().triggerVServerAuthForPlayer(sender)
        return Command.SINGLE_SUCCESS
    }

    private fun executeUuid(sender: CommandSource): Int {
        if (sender !is Player) {
            sender.sendPlainMessage("§c该命令只能由玩家执行")
            return Command.SINGLE_SUCCESS
        }

        val proxyPlayer = sender
        val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(proxyPlayer)
        val profileService = HyperZoneProfileServiceProvider.get()
        val profile = profileService.getAttachedProfile(hyperZonePlayer)

        sender.sendPlainMessage("§e[ProxyPlayer] name=${proxyPlayer.username} uuid=${proxyPlayer.uniqueId}")
        sender.sendPlainMessage(
            "§e[ClientOriginal][UNTRUSTED] name=${hyperZonePlayer.clientOriginalName} uuid=${hyperZonePlayer.clientOriginalUUID}"
        )
        sender.sendPlainMessage(
            "§e[HyperZonePlayer] verified=${hyperZonePlayer.isVerified()} attachedProfile=${hyperZonePlayer.hasAttachedProfile()} waitingArea=${hyperZonePlayer.isInWaitingArea()} canResolveOrCreateProfile=${profileService.canResolveOrCreateProfile(hyperZonePlayer.clientOriginalName)} credentials=${hyperZonePlayer.getSubmittedCredentials().size}"
        )
        if (profile != null) {
            sender.sendPlainMessage("§e[Profile] id=${profile.id} name=${profile.name} uuid=${profile.uuid}")
        } else {
            sender.sendPlainMessage("§e[Profile] null")
        }

        return Command.SINGLE_SUCCESS
    }

    private fun executeBindCodeGenerate(sender: CommandSource): Int {
        if (sender !is Player) {
            sender.sendPlainMessage("§c该命令只能由玩家执行")
            return Command.SINGLE_SUCCESS
        }

        val hyperZonePlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(sender)
        }.getOrElse {
            sender.sendPlainMessage("§c当前无法获取登录态玩家对象")
            return Command.SINGLE_SUCCESS
        }

        sender.sendMessage(bindingCodeService.generate(hyperZonePlayer).message)
        return Command.SINGLE_SUCCESS
    }

    private fun executeBindCodeUse(sender: CommandSource, code: String): Int {
        if (sender !is Player) {
            sender.sendPlainMessage("§c该命令只能由玩家执行")
            return Command.SINGLE_SUCCESS
        }

        val hyperZonePlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(sender)
        }.getOrElse {
            sender.sendPlainMessage("§c当前无法获取登录态玩家对象")
            return Command.SINGLE_SUCCESS
        }

        sender.sendMessage(bindingCodeService.use(hyperZonePlayer, code).message)
        return Command.SINGLE_SUCCESS
    }

    companion object {
        private const val ADMIN_PERMISSION = "hyperzonelogin.admin"
    }
} 