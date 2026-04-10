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
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager

class HyperZoneLoginCommand {
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
        )
    }

    private fun showUsage(sender: CommandSource) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendPlainMessage("§e/hzl reload")
        }
        sender.sendPlainMessage("§e/hzl re")
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
        HyperZoneLoginMain.getInstance().triggerLimboAuthForPlayer(sender)
        return Command.SINGLE_SUCCESS
    }

    private fun executeUuid(sender: CommandSource): Int {
        if (sender !is Player) {
            sender.sendPlainMessage("§c该命令只能由玩家执行")
            return Command.SINGLE_SUCCESS
        }

        val proxyPlayer = sender
        val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(proxyPlayer)
        val profile = hyperZonePlayer.getDBProfile()

        sender.sendPlainMessage("§e[ProxyPlayer] name=${proxyPlayer.username} uuid=${proxyPlayer.uniqueId}")
        sender.sendPlainMessage(
            "§e[HyperZonePlayer] verified=${hyperZonePlayer.isVerified()} attachedProfile=${hyperZonePlayer.hasAttachedProfile()} waitingArea=${hyperZonePlayer.isInWaitingArea()} canResolveOrCreateProfile=${hyperZonePlayer.canResolveOrCreateProfile()}"
        )
        if (profile != null) {
            sender.sendPlainMessage("§e[Profile] id=${profile.id} name=${profile.name} uuid=${profile.uuid}")
        } else {
            sender.sendPlainMessage("§e[Profile] null")
        }

        return Command.SINGLE_SUCCESS
    }

    companion object {
        private const val ADMIN_PERMISSION = "hyperzonelogin.admin"
    }
} 