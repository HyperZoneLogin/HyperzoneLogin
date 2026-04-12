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

import com.mojang.brigadier.arguments.StringArgumentType
import com.velocitypowered.api.command.BrigadierCommand
import icu.h2l.api.command.HyperChatBrigadierRegistration

object BindCodeBrigadierCommands {
    fun bindCode(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .then(
                    BrigadierCommand.literalArgumentBuilder("generate")
                        .executes { commandContext ->
                            context.execute(commandContext.source, args = arrayOf("generate"))
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("use")
                        .then(
                            BrigadierCommand.requiredArgumentBuilder("code", StringArgumentType.word())
                                .executes { commandContext ->
                                    context.execute(
                                        commandContext.source,
                                        args = arrayOf(
                                            "use",
                                            StringArgumentType.getString(commandContext, "code")
                                        )
                                    )
                                }
                        )
                )
        }
    }
}

