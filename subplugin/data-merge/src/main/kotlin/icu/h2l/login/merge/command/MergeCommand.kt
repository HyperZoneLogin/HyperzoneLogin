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

package icu.h2l.login.merge.command

import com.mojang.brigadier.Command
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import icu.h2l.login.merge.MergeMessages
import java.util.concurrent.atomic.AtomicBoolean

class MergeCommand(
    private val runMlMigration: () -> String,
    private val runAmMigration: () -> String
) {
    private val running = AtomicBoolean(false)

    fun createCommand(): BrigadierCommand {
        return BrigadierCommand(
            BrigadierCommand.literalArgumentBuilder("hzl-merge")
                .requires { source -> source.hasPermission(ADMIN_PERMISSION) }
                .executes { context ->
                    showUsage(context.source)
                    Command.SINGLE_SUCCESS
                }
                .then(
                    BrigadierCommand.literalArgumentBuilder("ml")
                        .executes { context ->
                            executeMigration(context.source, "ml", runMlMigration)
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("am")
                        .executes { context ->
                            executeMigration(context.source, "am", runAmMigration)
                        }
                )
        )
    }

    private fun showUsage(sender: CommandSource) {
        sender.sendMessage(MergeMessages.usageMl(sender))
        sender.sendMessage(MergeMessages.usageAm(sender))
    }

    private fun executeMigration(
        sender: CommandSource,
        subCommand: String,
        action: () -> String
    ): Int {
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage(MergeMessages.alreadyRunning(sender))
            return Command.SINGLE_SUCCESS
        }

        try {
            sender.sendMessage(
                if (subCommand == "ml") MergeMessages.startMl(sender) else MergeMessages.startAm(sender)
            )
            val summary = action()

            sender.sendMessage(MergeMessages.completed(sender, summary))
            sender.sendMessage(
                MergeMessages.detailLog(sender, if (subCommand == "ml") "merge-ml.log" else "merge-am.log")
            )
        } catch (ex: Exception) {
            sender.sendMessage(MergeMessages.failed(sender, ex.message))
        } finally {
            running.set(false)
        }

        return Command.SINGLE_SUCCESS
    }

    companion object {
        private const val ADMIN_PERMISSION = "hyperzonelogin.admin"
    }
}
